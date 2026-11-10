"""Assemble a private O3 arithmetic probe around the pinned 3-tensor DMA ABI."""
import argparse
import hashlib
import struct
from pathlib import Path


TEMPLATE_SHA = 'fdb457e0fc75d85d6914e2bc4c1115c1a6f72e1e3a08a214b638260217c6aa84'
LIBRARY_SHA = '7ca99ac6fc3f3cc462919b77de73323c8085f51244621014c69e18cfaade8814'
NOP = 0xd400000000000000
WAIT_LOAD = 0xd600040100000000
# Same workgroup shared-memory completion sequence as the verified DMA template.
SYNC = [0xd600070100000000, 0xd600030200000000, 0xd4c00f0600000000]
W_BANK = 32768
Y_BANK = 65536
DOT_BANK = 81920
SCALE_BANK = 83968
ACC_BANK = 86016
PLANE = 512 * 4


def imm(dst, value):
    return 0x70200f0200000000 | dst << 44 | value


def alu(op, dst, left, right):
    return 0x60200f0000000100 | dst << 44 | op << 32 | right << 12 | left


def fma(dst, left, right, acc):
    return 0x40200f0100000100 | dst << 44 | acc << 24 | right << 12 | left


def read(dst, address):
    return 0xa000000004100000 | dst << 44 | address << 12


def write(src, address):
    return 0xa200000004100000 | src << 44 | address << 12


def scalar_op(op, dst, src, rhs):
    return 0xd0000f0000000000 | dst << 44 | op << 32 | rhs << 12 | src


def scalar_imm(dst, value):
    return 0xd2000f0100000000 | dst << 44 | value


def matrix_epilogue(kind, panel, frame_tile, weight_bank):
    if panel not in (16,32,64):
        raise ValueError('matrix panel must have 16, 32 or 64 output channels')
    code=[imm(1,2),alu(0x33,0,0,1)]
    def fetch(dst,base,offset):
        code.extend([imm(1,base),alu(0x31,1,offset,1),read(dst,1)])
    if kind=='lane-finish':
        code.append(alu(0x33,2,0,1))  # first of 4 lanes for this output
        for j in range(8):
            fetch(8+j,(j//4)*panel*16+(j%4)*4,2)
        fetch(3,W_BANK,0);fetch(4,W_BANK+panel*4,0)
        code.append(WAIT_LOAD)
        for j in (8,10,12,14):
            code.append(alu(1,j,j,j+1))
        code.extend([alu(1,8,8,10),alu(1,12,12,14),alu(1,5,8,12),
                     alu(1,5,5,3),alu(1,5,5,4)])
    else:
        # Reuse the same two-per-channel scales for consecutive frames.
        code.extend([imm(1,4),alu(0x34,6,0,1),imm(1,2),alu(0x33,6,6,1)])
        fetch(4,weight_bank,6)
        code.extend([scalar_imm(26,32//frame_tile-1),scalar_op(0x20,27,14,26),
                     scalar_imm(26,3+frame_tile.bit_length()-1),scalar_op(0x23,27,27,26),0x70208f010000001b,
                     imm(1,(panel*16).bit_length()-1),alu(0x34,6,0,1),
                     imm(1,2),alu(0x33,6,6,1),alu(0x31,6,8,6)])
        row=panel*8*4
        for frame in range(frame_tile):
            fetch(3,frame*row,0)
            if kind=='lane-first':code.append(imm(5,0))
            else:fetch(5,(frame_tile+frame)*row,0)
            fetch(7,weight_bank+panel*2*4+frame*8,6)
            code.extend([WAIT_LOAD,alu(0,7,4,7),fma(5,3,7,5),
                         imm(1,Y_BANK+frame*row),alu(0x31,1,0,1),write(5,1)])
        return code
    code.extend([imm(1,Y_BANK),alu(0x31,1,0,1),write(5,1)])
    return code


def body(kind):
    # r0 initially holds the local thread ordinal. Keep its byte offset in r0.
    code = [imm(1,2), alu(0x33,0,0,1)]
    def load(dst, base, index=0, ptr=1):
        code.append(imm(ptr,base))
        if index is not None:
            code.append(alu(0x31,ptr,index,ptr))
        code.append(read(dst,ptr))
    def store(src, base):
        code.extend([imm(1,base),alu(0x31,1,0,1),write(src,1)])
    if kind=='dot-update':
        # The unmodified INT8 matrix node provides the first operand, in L2B.
        load(3,0);load(4,W_BANK,ptr=2);load(5,W_BANK+PLANE,ptr=7)
        code.extend([WAIT_LOAD,fma(5,3,4,5)])
    elif kind in ('mul','tail-mul','fma'):
        at = 8*PLANE if kind=='tail-mul' else 0
        load(3,at);load(4,W_BANK+at,ptr=2)
        if kind=='fma':load(5,PLANE,ptr=7)
        code.extend([WAIT_LOAD, fma(5,3,4,5) if kind=='fma' else alu(0,5,3,4)])
    else:
        # One thread per block/lane. All eight integer products and the F32
        # product of the two original half scales are exact in binary32.
        code.append(imm(5,0))
        for term in range(8):
            load(3,term*PLANE);load(4,W_BANK+term*PLANE,ptr=2)
            code.extend([WAIT_LOAD,fma(5,3,4,5)])
        load(3,8*PLANE);load(4,W_BANK+8*PLANE,ptr=2)
        code.extend([WAIT_LOAD,alu(0,4,3,4)])
        store(5,DOT_BANK);store(4,SCALE_BANK)
        code.extend(SYNC)
        # The lane selector is independent of the data. Slots 0..7 are even
        # lanes 0..3 followed by odd lanes 0..3.
        load(6,9*PLANE)
        code.extend([WAIT_LOAD,imm(5,0)])
        for pair in range(64):
            load(3,DOT_BANK+pair*32,6);load(4,SCALE_BANK+pair*32,6,2)
            code.extend([WAIT_LOAD,fma(5,3,4,5)])
        store(5,ACC_BANK)
        code.extend(SYNC)
        for lane in range(8):
            load(8+lane,ACC_BANK+lane*4,None)
        code.append(WAIT_LOAD)
        for even in (8,10,12,14):
            code.append(alu(1,even,even,even+1))
        code.extend([alu(1,8,8,10),alu(1,12,12,14),alu(1,5,8,12)])
        load(3,W_BANK+9*PLANE,None);load(4,W_BANK+9*PLANE+4,None,2)
        code.append(WAIT_LOAD)
        if kind=='shared-q8':
            # Weights and bias are per output channel. The extra weight word is
            # that channel's byte offset into this frame's residual vector.
            load(4,10*PLANE,4,2)
            code.append(WAIT_LOAD)
        code.extend([alu(1,5,5,3),alu(1,5,5,4)])
    store(5,Y_BANK)
    return code


def assemble(template, kind, channels=1, frame_tile=1):
    if frame_tile not in (1,2,4,8,16,32) or (frame_tile!=1 and kind not in ('lane-first','lane-step')):
        raise ValueError('frame tiling is supported only for matrix lane updates')
    if kind.startswith('lane-') and frame_tile*channels*8*4>32768:
        raise ValueError('matrix tile exceeds the reserved DSM bank')
    if hashlib.sha256(template).hexdigest()==LIBRARY_SHA:
        # Read the existing ELF payload locally without altering the vendor library.
        template=template[0xa8433:0xa8433+2600]
    if hashlib.sha256(template).hexdigest()!=TEMPLATE_SHA:
        raise ValueError('unexpected research module template')
    words=list(struct.unpack_from('<112Q',template,0x180))
    if words[62]!=alu(1,1,1,2) or words[97]!=0xd400000100000000:
        raise ValueError('unrecognized arithmetic window')
    # DMA loads and stores still target only the three argument tensors. The
    # larger shared-memory banks accommodate the wider input payloads.
    weight_bank=98304 if kind.startswith('lane-') and frame_tile*channels*8*8>W_BANK else W_BANK
    words[48]=0xd2000f0100000000|weight_bank
    words[94]=0xd2001f0100000000|Y_BANK
    prefix=words[:55]
    if kind in ('shared-q8','shared-mul'):
        if channels<2 or channels>1024 or channels&(channels-1):
            raise ValueError('shared mapping requires a power-of-two channel count')
        # s14 is the workgroup ordinal. Map A to frame and B to channel; C
        # keeps the linear output ordinal. No device/system code is replaced.
        prefix[35:36]=[scalar_imm(26,channels-1),scalar_op(0x20,0,14,26)]
        prefix[13:14]=[scalar_imm(26,channels.bit_length()-1),scalar_op(0x24,6,14,26)]
        prefix[12]+=2   # original relative jump over the two input transfers
    if kind in ('lane-first','lane-step'):
        prefix[35:36]=[scalar_imm(26,(32//frame_tile).bit_length()-1),scalar_op(0x24,0,14,26)]
        prefix[12]+=1
    arithmetic=(matrix_epilogue(kind,channels,frame_tile,weight_bank) if kind.startswith('lane-')
                else body('mul' if kind=='shared-mul' else kind))
    program=prefix+arithmetic+words[66:98]
    program.extend([NOP]*((-len(program))%16))
    code=struct.pack('<'+'Q'*len(program),*program)
    growth=max(0,(len(code)-0x380+4095)//4096)*4096
    result=bytearray(template[:0x500]+bytes(growth)+template[0x500:])
    result[0x180:0x180+len(code)]=code
    shoff=0x768+growth
    struct.pack_into('<Q',result,40,shoff)
    for i in range(11):
        header=shoff+i*64
        addr,offset=struct.unpack_from('<QQ',result,header+16)
        if offset>=0x500:struct.pack_into('<Q',result,header+24,offset+growth)
        if i==3:struct.pack_into('<Q',result,header+16,addr+growth)
        if i==2:struct.pack_into('<Q',result,header+32,len(code))
    for i in range(5):
        header=64+i*56
        offset,va,pa=struct.unpack_from('<QQQ',result,header+8)
        if i==2:struct.pack_into('<QQ',result,header+32,0x28+len(code),0x28+len(code))
        if i==3:struct.pack_into('<QQQ',result,header+8,offset+growth,va+growth,pa+growth)
    # Update the two symbols and the vector register count in the kernel ABI.
    struct.pack_into('<Q',result,0x638+growth+2*24+8,0x12600+growth)
    struct.pack_into('<Q',result,0x638+growth+3*24+16,len(code))
    result[0x601+growth]=16
    return result


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('template',type=Path,help='Pinned O3 libxnnl.so or its original DemoAdd module')
    parser.add_argument('kind',choices=['mul','tail-mul','fma','q8','shared-mul','shared-q8','dot-update',
                                        'lane-first','lane-step','lane-finish'])
    parser.add_argument('output',type=Path)
    parser.add_argument('--channels',type=int,default=1)
    parser.add_argument('--frame-tile',type=int,default=1)
    args=parser.parse_args()
    data=assemble(args.template.read_bytes(),args.kind,args.channels,args.frame_tile)
    with args.output.open('xb') as dest:dest.write(data)
    print(args.kind,len(data),hashlib.sha256(data).hexdigest())

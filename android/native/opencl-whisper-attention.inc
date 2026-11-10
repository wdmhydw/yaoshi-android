        // OpenCL can fuse the encoder's bidirectional attention, avoiding a
        // full [1500,1500,heads] score/probability pair for every layer.
        ggml_tensor* Vheads = ggml_cont(ctx, ggml_permute(ctx,
            ggml_reshape_3d(ctx, v, hd, H, T), 0, 2, 1, 3));
        ggml_tensor* fused = ggml_flash_attn_ext(ctx, ggml_cont(ctx,Q),
            ggml_cont(ctx,K), Vheads, bidirectional_mask, kq_scale, 0.0f, 0.0f);
        if (ggml_backend_supports_op(backend(),fused)) {
            opencl_fused = true;
            if (il == 0) MT_LOGI("OpenCL encoder: fused bidirectional attention");
            ggml_flash_attn_ext_set_prec(fused,GGML_PREC_F32);
            x = ggml_cont_2d(ctx,fused,d,T);
        } else {
            ggml_tensor* scores = ggml_mul_mat(ctx,K,Q);
            ggml_mul_mat_set_prec(scores,GGML_PREC_F32);
            ggml_tensor* attn = ggml_soft_max_ext(ctx,scores,nullptr,kq_scale,0.0f);
            ggml_tensor* V = ggml_cont(ctx,ggml_permute(ctx,ggml_reshape_3d(ctx,v,hd,H,T),1,2,0,3));
            ggml_tensor* KQV = ggml_mul_mat(ctx,V,attn);
            x = ggml_cont_2d(ctx,ggml_permute(ctx,KQV,0,2,1,3),d,T);
        }

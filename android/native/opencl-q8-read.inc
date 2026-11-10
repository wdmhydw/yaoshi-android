        // Partial reads of plain SoA Q8 rows must not reconstruct the whole
        // embedding table. MOSS reads one 1 KB row from a ~165 MB tensor.
        const size_t block_bytes = ggml_type_size(GGML_TYPE_Q8_0);
        const size_t block_values = ggml_blck_size(GGML_TYPE_Q8_0);
        bool transposed = false;
#ifdef GGML_OPENCL_USE_ADRENO_KERNELS
        transposed = enable_adreno_trans_weight(backend_ctx, tensor);
#endif
        if (!transposed && size < ggml_nbytes(tensor) &&
                offset % block_bytes == 0 && size % block_bytes == 0) {
            GGML_ASSERT(block_bytes == sizeof(ggml_fp16_t) + block_values);
            const size_t first = offset / block_bytes, count = size / block_bytes;
            if (count == 0) return;
            std::vector<ggml_fp16_t> scales(count);
            std::vector<uint8_t> quants(count * block_values);
            CL_CHECK(clEnqueueReadBuffer(queue, extra->d, CL_TRUE,
                first * sizeof(ggml_fp16_t), count * sizeof(ggml_fp16_t), scales.data(), 0, nullptr, nullptr));
            CL_CHECK(clEnqueueReadBuffer(queue, extra->q, CL_TRUE,
                first * block_values, quants.size(), quants.data(), 0, nullptr, nullptr));
            auto* dest = static_cast<uint8_t*>(data);
            for (size_t i = 0; i < count; ++i) {
                memcpy(dest + i * block_bytes, &scales[i], sizeof(ggml_fp16_t));
                memcpy(dest + i * block_bytes + sizeof(ggml_fp16_t),
                    quants.data() + i * block_values, block_values);
            }
            return;
        }

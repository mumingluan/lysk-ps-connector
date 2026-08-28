package com.axuan.lyskps;

interface IShizukuRsaService {
    String restore(boolean deleteMetadata) = 1;
    String patch(long off2048, long off1024, in byte[] replacement2048, in byte[] replacement1024) = 2;
    void destroy() = 16777114;
}

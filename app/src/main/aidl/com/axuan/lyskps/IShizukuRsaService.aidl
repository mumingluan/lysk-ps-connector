package com.axuan.lyskps;

interface IShizukuRsaService {
    String restore(boolean deleteMetadata) = 1;
    void destroy() = 16777114;
}

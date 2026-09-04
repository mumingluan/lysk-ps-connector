package com.axuan.lyskps;

import android.os.ParcelFileDescriptor;

interface IShizukuRsaService {
    String restore(boolean deleteIl2cpp) = 1;
    String patch(long off2048, long off1024, in byte[] replacement2048, in byte[] replacement1024) = 2;
    byte[] readRsaBlocks(long off2048, long off1024) = 3;
    String installNls(in ParcelFileDescriptor sourceZip, in ParcelFileDescriptor sourceNx,
        in ParcelFileDescriptor backupZip, in ParcelFileDescriptor backupNx,
        String zipName, String nxName) = 4;
    String restoreNls(in ParcelFileDescriptor backupZip, in ParcelFileDescriptor backupNx,
        String zipName, String nxName) = 5;
    String deleteNls(String zipName, String nxName) = 6;
    String restoreMetadataFromApk() = 7;
    void destroy() = 16777114;
}

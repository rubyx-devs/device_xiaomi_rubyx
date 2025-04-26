#!/usr/bin/env -S PYTHONPATH=../../../tools/extract-utils python3
#
# SPDX-FileCopyrightText: 2024 The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

from extract_utils.fixups_blob import (
    blob_fixup,
    blob_fixups_user_type,
)
from extract_utils.fixups_lib import (
    lib_fixups,
    lib_fixups_user_type,
)
from extract_utils.main import (
    ExtractUtils,
    ExtractUtilsModule,
)

namespace_imports = [
    "device/xiaomi/rubyx",
    'hardware/xiaomi',
    'hardware/mediatek',
    "hardware/mediatek/libmtkperf_client",
]

lib_fixups: lib_fixups_user_type = {
    **lib_fixups,
}

blob_fixups: blob_fixups_user_type = {
    "system_ext/priv-app/ImsService/ImsService.apk": blob_fixup()
        .apktool_patch("blob-patches/ImsService.patch", "-r"),
    "system_ext/lib64/libsource.so": blob_fixup()
        .add_needed("libui_shim.so"),
    "system_ext/lib64/libimsma.so": blob_fixup()
        .regex_replace("libsink.so", "libsink-mtk.so"),
    "system_ext/lib64/libsink-mtk.so": blob_fixup()
        .add_needed("libaudioclient_shim.so"),
    "vendor/bin/hw/android.hardware.gnss-service.mediatek": blob_fixup()
        .replace_needed("android.hardware.gnss-V1-ndk_platform.so", "android.hardware.gnss-V1-ndk.so"),
    "vendor/bin/hw/android.hardware.media.c2@1.2-mediatek-64b": blob_fixup()
        .add_needed("libstagefright_foundation-v33.so")
        .replace_needed("libavservices_minijail_vendor.so", "libavservices_minijail.so"),
    "vendor/etc/init/android.hardware.neuralnetworks@1.3-service-mtk-neuron.rc": blob_fixup()
        .regex_replace("start", "enable"),
    "vendor/firmware/txpowerctrl_*": blob_fixup()
        .regex_replace("\t", ""),
    "vendor/bin/mnld": blob_fixup()
        .replace_needed("libsensorndkbridge.so", "android.hardware.sensors@1.0-convert-shared.so"),
    "vendor/lib*/libaalservice.so": blob_fixup()
        .replace_needed("libsensorndkbridge.so", "android.hardware.sensors@1.0-convert-shared.so"),
    "vendor/lib64/hw/audio.primary.mt6877.so": blob_fixup()
        .add_needed("libstagefright_foundation-v33.so")
        .replace_needed("libalsautils.so", "libalsautils-v31.so"),
    "vendor/lib64/libmnl.so": blob_fixup()
        .add_needed("libcutils.so"),
    "vendor/lib64/libteei_daemon_vfs.so": blob_fixup()
        .add_needed("liblog.so"),
    ("vendor/lib64/libnvram.so", "vendor/lib64/libsysenv.so", "vendor/bin/hw/android.hardware.neuralnetworks@1.3-service-mtk-neuron"): blob_fixup()
        .add_needed("libbase_shim.so"),
    ("vendor/lib*/libdlbdsservice.so", "vendor/lib*/soundfx/libswdap.so"): blob_fixup()
        .replace_needed("libstagefright_foundation.so", "libstagefright_foundation-v33.so"),
}

module = ExtractUtilsModule(
    'rubyx',
    'xiaomi',
    blob_fixups=blob_fixups,
    lib_fixups=lib_fixups,
    namespace_imports=namespace_imports,
    add_firmware_proprietary_file=True,
)

if __name__ == '__main__':
    utils = ExtractUtils.device(module)
    utils.run()
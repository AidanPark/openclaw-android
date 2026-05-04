package com.openclaw.android.core.proot

/**
 * Constantes para la gestión de proot.
 *
 * Responsabilidad única: contener URLs, nombres de archivos y constantes
 * relacionadas con proot y rootfs Ubuntu.
 */
internal object ProotConstants {

    const val TAG = "ProotManager"

    // URL del binario proot estático para arm64 (Termux packages, GPL v2)
    // Versión 5.4.0 — estable, probada en Android 7-15
    const val PROOT_URL_ARM64 =
        "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.4.0_aarch64.deb"

    // URL del rootfs Ubuntu 24.04 minimal para arm64 (proot-distro)
    // ~80MB comprimido, ~250MB extraído
    const val UBUNTU_ROOTFS_URL =
        "https://github.com/termux/proot-distro/releases/download/v4.22.0/ubuntu-aarch64-pd-v4.22.0.tar.xz"

    // Fallback: Ubuntu 22.04 LTS (más estable en dispositivos viejos)
    const val UBUNTU_ROOTFS_URL_FALLBACK =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/22.04/release/ubuntu-base-22.04.1-base-arm64.tar.gz"

    // Nombres de archivos en cache
    const val PROOT_DEB_FILENAME = "proot.deb"
    const val PROOT_DATA_TAR_FILENAME = "proot-data.tar.xz"
    const val UBUNTU_ROOTFS_TAR_FILENAME = "ubuntu-rootfs.tar.xz"

    // Rutas dentro del filesystem de la app
    const val PROOT_BIN_PATH = "bin/proot"
    const val ROOTFS_DIR_PATH = "ubuntu-rootfs"
    const val HOME_DIR_PATH = "home"

    // Rutas dentro del .deb de Termux
    val PROOT_BINARY_PATHS_IN_DEB = setOf(
        "data/data/com.termux/files/usr/bin/proot",
        "./data/data/com.termux/files/usr/bin/proot",
        "usr/bin/proot",
        "./usr/bin/proot",
    )
}

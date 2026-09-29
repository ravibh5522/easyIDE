package dev.easyide.sandbox.bootstrap

import dev.easyide.sandbox.files.SafeTree
import java.io.File

/**
 * Keeps npm's cache from corrupting itself under proot.
 *
 * proot runs with `--link2symlink` (dpkg needs it), so `link(2)` leaves the bytes in a hidden
 * `.l2s.*` file next to the original and makes both names symlinks. cacache writes a blob to
 * `_cacache/tmp`, then hard-links it into `content-v2` and unlinks the tmp name: the real bytes
 * stay in `tmp`, and every cached blob is a symlink into it. `npm cache verify` (which npm's own
 * error messages suggest) and any other cleaning of `tmp` then deletes the bytes, and from that
 * moment `npm install` and `npm cache verify` fail with `ENOENT ... content-v2/sha512/...`.
 *
 * [PRELOAD] is a Node preload (`NODE_OPTIONS=--require`, set by the proot launcher when the file
 * exists) that makes `fs.link` copy instead when the destination is inside a `_cacache`
 * directory, so a cached blob is an ordinary file. [purgeLegacyCache] removes the caches earlier
 * builds already broke, once per environment: a cache holds nothing that cannot be downloaded again.
 */
internal object NodeCacheGuard {

    const val PRELOAD_GUEST_PATH = "/usr/local/lib/easyide/fs-link-copy.cjs"
    private const val PURGE_MARKER = "npm-cache-purged"
    private const val CACHE_DIR = "root/.npm/_cacache"

    fun ensure(rootfs: File) {
        val preload = File(rootfs, PRELOAD_GUEST_PATH.removePrefix("/"))
        if (!preload.isFile || preload.readText() != PRELOAD) {
            preload.parentFile.mkdirs()
            preload.writeText(PRELOAD)
        }
        purgeLegacyCache(rootfs)
    }

    private fun purgeLegacyCache(rootfs: File) {
        val marker = File(rootfs.parentFile ?: return, PURGE_MARKER)
        if (marker.exists()) return
        SafeTree.deleteRecursively(File(rootfs, CACHE_DIR))
        marker.writeText("npm's cache was cleared once because earlier builds stored blobs as dangling proot links\n")
    }

    val PRELOAD = """
        'use strict';
        // easyIDE: under proot a hard link is a symlink to a hidden file that a cache clean deletes.
        // npm's cache (cacache) must hold real files, so a link into a _cacache directory becomes a copy.
        const fs = require('fs');
        const inCache = (dest) => typeof dest === 'string' && dest.includes('/_cacache/');
        const { link, linkSync } = fs;
        const plink = fs.promises.link;
        const EXCL = fs.constants.COPYFILE_EXCL;
        fs.link = function (src, dest, cb) {
          return inCache(dest) ? fs.copyFile(src, dest, EXCL, cb) : link.apply(this, arguments);
        };
        fs.linkSync = function (src, dest) {
          return inCache(dest) ? fs.copyFileSync(src, dest, EXCL) : linkSync.apply(this, arguments);
        };
        fs.promises.link = function (src, dest) {
          return inCache(dest) ? fs.promises.copyFile(src, dest, EXCL) : plink.apply(this, arguments);
        };
    """.trimIndent() + "\n"
}

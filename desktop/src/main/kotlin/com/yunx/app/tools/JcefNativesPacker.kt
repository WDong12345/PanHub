package com.yunx.app.tools

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.X86Options
import org.tukaani.xz.XZOutputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * 打包期工具（不参与运行）：把 jcef 原生包 jar 里内嵌的 `tar.gz` 重压成 `tar.xz`。
 *
 * 为什么这么做：CEF 原生库是发布包里最大的一块（解包后 261MB，官方 jar 内 gzip 后 120MB）。
 * 换成 LZMA2 后同一批文件约 90MB，**内容一个不少**，只是压缩率更高。
 * 运行期由 [com.yunx.app.ui.jcef.JcefNativesInstaller] 解开，再让 jcefmaven 跳过它自己的安装步骤。
 *
 * 顺带把 Chromium 的语言包裁到只剩 `zh-CN`（应用固定 `settings.locale = "zh-CN"`，
 * 其余语言包永远不会被加载），原先这一步由 portable-package.ps1 里的 tar 拆包/回包完成。
 *
 * 用法：`JcefNativesPacker <jcef-natives-*.jar> <out.tar.xz>`
 */
object JcefNativesPacker {

    /** 保留的语言包（与 JcefHolder 的 settings.locale 一致） */
    private val KEEP_LOCALES = setOf("zh-CN")

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size >= 2) { "用法: JcefNativesPacker <jcef-natives-*.jar> <out.tar.xz>" }
        val jar = File(args[0])
        val out = File(args[1])
        require(jar.isFile) { "原生包不存在: $jar" }
        out.parentFile?.mkdirs()

        var files = 0
        ZipFile(jar).use { zip ->
            val entry = zip.entries().asSequence().firstOrNull { it.name.endsWith(".tar.gz") }
                ?: error("jar 内没有 .tar.gz: $jar")
            zip.getInputStream(entry).use { rawIn ->
                GzipCompressorInputStream(rawIn, true).use { gzipIn ->
                    TarArchiveInputStream(gzipIn).use { tarIn ->
                        val xzOut = XZOutputStream(
                            BufferedOutputStream(FileOutputStream(out), BUFFER_SIZE),
                            // BCJ(x86) 前置滤波器：libcef.dll 这类 PE 文件能再小 1~2%，
                            // 对非代码数据基本无影响（与 `xz --x86` 同一做法）
                            arrayOf(X86Options(), LZMA2Options(PRESET))
                        )
                        xzOut.use { xz ->
                            TarArchiveOutputStream(xz).use { tarOut ->
                                tarOut.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                                while (true) {
                                    val entryIn = tarIn.nextEntry ?: break
                                    // commons-compress 解析时会去掉 tar 里的 "./" 前缀，这里统一规范化
                                    val name = entryIn.name.removePrefix("./")
                                    if (!keep(name)) continue
                                    val entryOut = TarArchiveEntry(name).apply {
                                        mode = entryIn.mode
                                        modTime = entryIn.modTime
                                        if (!entryIn.isDirectory) size = entryIn.size
                                    }
                                    tarOut.putArchiveEntry(entryOut)
                                    if (!entryIn.isDirectory) {
                                        tarIn.copyTo(tarOut, BUFFER_SIZE)
                                        files++
                                    }
                                    tarOut.closeArchiveEntry()
                                }
                            }
                        }
                    }
                }
            }
        }
        println(
            "jcef natives repacked: ${out.name} ${out.length() / 1024 / 1024} MB " +
                "(from ${jar.name} ${jar.length() / 1024 / 1024} MB, $files files, preset $PRESET)"
        )
    }

    /** 只裁语言包；其余（libcef.dll / icudtl.dat / *.pak / *.dll / *.exe / *.bin）一律保留 */
    private fun keep(name: String): Boolean {
        if (!name.startsWith("locales/")) return true
        val leaf = name.removePrefix("locales/").trimEnd('/')
        return leaf.isEmpty() || leaf.removeSuffix(".pak") in KEEP_LOCALES
    }

    private const val BUFFER_SIZE = 1 shl 16

    /** LZMA2 预设：6 在体积与耗时之间比较平衡（比 gzip 小约 25%） */
    private const val PRESET = 6
}

package top.niunaijun.blackobfuscator

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import com.android.build.api.variant.ApplicationVariant
import top.niunaijun.blackobfuscator.core.ObfDex

/**
 * BlackObfuscator AGP 7+ / 8.x 适配版。
 *
 * 相对原版（AGP 3.3/4.2 时代）的关键变更：
 *  1. 弃用 afterEvaluate + applicationVariants + 硬拼老任务名（AGP 7+ 已移除，
 *     对应 issues #9/#5/#3 "This gradle version is not applicable"），
 *     改用 androidComponents.onVariants + tasks.configureEach 挂载 mergeDex&lt;Variant&gt;。
 *  2. 配置缓存安全：所有闭包只捕获简单值 / Provider，不持有 Project、Android 扩展
 *     或 Variant 引用（修复 issues #36/#20 的 groovy.lang.Reference 序列化失败）。
 *  3. 只处理 application 变体（对齐原行为；library / 测试变体自动跳过）。
 *  4. R8 mapping 文件按 AGP 惯例路径 build/outputs/mapping/&lt;variant&gt;/mapping.txt
 *     解析（minify 变体才存在），用于把 R8 混淆过的类名还原为原名再匹配白名单。
 *  5. 挂载全部候选 dex 产出任务（minify&lt;Variant&gt;WithR8 / mergeDex /
 *     mergeProjectDex / mergeLibDex / mergeExtDex），configureEach 天然跳过
 *     当前 AGP 版本不存在的任务，覆盖 AGP 7.x 与 8.x 的差异。
 */
class ObfPlugin implements Plugin<Project> {

    private static final String PLUGIN_NAME = "BlackObfuscator"

    @Override
    void apply(Project project) {
        def extension = project.extensions.create(PLUGIN_NAME, BlackObfuscatorExtension, project)

        def androidComponents = project.extensions.findByType(
                Class.forName('com.android.build.api.variant.AndroidComponentsExtension'))
        if (androidComponents == null) {
            project.logger.warn("BlackObfuscator: Android Gradle Plugin 7.0+ not found, plugin disabled.")
            return
        }

        androidComponents.onVariants(androidComponents.selector().all()) { variant ->
            // 仅处理 application 变体（library / androidTest / unitTest 跳过）
            if (!(variant instanceof ApplicationVariant)) {
                return
            }

            // AGP 7/8 最终 dex 的产出任务随构建类型与 AGP 版本而异：
            //   - minify 变体：minify<Variant>WithR8（R8 直接产出 dex）
            //   - 非 minify 变体（AGP 8.7+ 无 mergeDex 任务）：
            //     mergeProjectDex<Variant> / mergeLibDex<Variant> / mergeExtDex<Variant>
            //   - 旧版 AGP 7.x 另有 mergeDex<Variant>
            // 不依赖 variant.buildType.minifyEnabled（AGP 8 在 onVariants 回调中读取
            // 该 DSL Property 可能抛异常），而是把全部候选任务名都挂上，
            // configureEach 天然跳过不存在的任务。
            final String[] dexTaskNames = [
                    // AGP 8（minSdk>=21）下 mergeDex<Variant> 任务不存在（单 dex 直接打包），
                    // 因此必须挂在实际存在的中间产物任务上：
                    // mergeProjectDex<Variant>（项目源码 dex，含 app 类）
                    // + mergeExtDex<Variant>（依赖库 dex，含 androidx 等）
                    // 注意：混淆的是中间产物 splitclasses.dex.dex，AGP 后续打包会直接使用混淆后的文件。
                    "minify" + capitalize(variant.name) + "WithR8",
                    "mergeDex" + capitalize(variant.name),
                    "mergeProjectDex" + capitalize(variant.name),
                    "mergeLibDex" + capitalize(variant.name),
                    "mergeExtDex" + capitalize(variant.name),
            ]

            // android.jar 路径（D8 二次转换的 library classpath，消除接口默认方法 desugaring warning）
            final String androidJar = resolveAndroidJar(project)

            project.logger.lifecycle("BlackObfuscator[diag]: variant={} androidJar={} tasks={}",
                    variant.name, androidJar, dexTaskNames.join(","))

            // 在配置阶段读取并拷贝配置值，避免执行期闭包持有扩展/项目引用
            final boolean enabled = extension.enabled
            final int depth = extension.depth
            final String[] obfClass = extension.obfClass
            final String[] blackClass = extension.blackClass
            // mapping.txt 路径 Provider（配置缓存安全；非 minify 时文件不存在，解析为空映射，无害）
            final Provider<RegularFile> mappingProvider =
                    project.layout.buildDirectory.file("outputs/mapping/" + variant.name + "/mapping.txt")

            project.tasks.configureEach { Task task ->
                if (task.name in dexTaskNames) {
                    task.doLast {
                        if (!enabled) {
                            task.logger.info("BlackObfuscator: disabled, skip {}", task.name)
                            return
                        }
                        String mappingFile = null
                        try {
                            RegularFile rf = mappingProvider.getOrNull()
                            if (rf != null && rf.asFile.exists()) {
                                mappingFile = rf.asFile.absolutePath
                            }
                        } catch (Throwable ignored) {
                            // mapping 不可用时忽略
                        }
                        int dexCount = 0
                        task.outputs.files.files.each { File f ->
                            task.logger.lifecycle("BlackObfuscator: processing output {}", f.absolutePath)
                            if (f.isDirectory() || f.name.endsWith('.dex')) {
                                ObfDex.obf(f.absolutePath, depth, obfClass, blackClass, mappingFile, androidJar)
                                dexCount++
                            }
                        }
                        task.logger.lifecycle("BlackObfuscator: obfuscated {} dex output(s) in {}", dexCount, task.name)
                    }
                }
            }
        }
    }

    private static String capitalize(String value) {
        return value ? value.substring(0, 1).toUpperCase() + value.substring(1) : value
    }

    private static String resolveAndroidJar(Project project) {
        try {
            def androidExt = project.extensions.findByName('android')
            project.logger.lifecycle("BlackObf[diag]: androidExt={}", androidExt?.class?.name)
            if (androidExt == null) {
                return null
            }
            def sdkDir = androidExt.sdkDirectory
            def compileSdk = androidExt.compileSdkVersion
            if (sdkDir != null && compileSdk != null) {
                String cs = String.valueOf(compileSdk)
                // AGP 8 的 compileSdkVersion 可能是 "android-34" 或 "34"
                String apiLevel = cs.contains('-') ? cs.substring(cs.lastIndexOf('-') + 1) : cs
                def jar = new File(sdkDir, "platforms/android-${apiLevel}/android.jar")
                project.logger.lifecycle("BlackObf[diag]: androidJarCandidate={} exists={}", jar.absolutePath, jar.exists())
                return jar.exists() ? jar.absolutePath : null
            }
        } catch (Throwable t) {
            project.logger.lifecycle("BlackObf[diag]: resolveAndroidJar error: {}", t.toString())
        }
        return null
    }
}

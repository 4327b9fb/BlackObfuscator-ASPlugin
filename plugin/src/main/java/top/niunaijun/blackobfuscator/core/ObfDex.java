package top.niunaijun.blackobfuscator.core;

import org.jf.DexLib2Utils;
import org.jf.util.TrieTree;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Created by Milk on 2021/12/17.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class ObfDex {

    /**
     * 隔离 classloader：child-first 加载核心库 + 指定版本 R8（默认 8.3.37）。
     * 实测 AGP 9.2.x 宿主 R8 9.2.14 对混淆后的控制流存在 asDexWritableCode 循环引用 bug，
     * Jar2Dex 转换会崩溃；其余 AGP 未逐一验证，必须让 Jar2Dex / D8 来自与宿主无关的类域以防御未知风险。
     * 诊断输出用 System.out/err 而非 Gradle logger：本类运行在隔离 classloader 中，
     * 刻意不依赖 Gradle API；Gradle 会捕获 System.out 并入构建日志，前缀便于检索。
     */
    private static volatile ClassLoader isolatedLoader;
    /** 已构建 loader 的 jar 集合签名；签名相同则复用，避免多 module / daemon 重复加载 R8。 */
    private static volatile String isolatedLoaderKey = "";

    /**
     * 构建隔离 classloader：jar 集合 = 扩展指定版本的 R8 jar + 插件 classpath 上的核心库 jar + 插件自身 jar。
     * - 由 ObfPlugin 在 afterEvaluate（用户配置块已执行）后调用；
     * - 同名 jar 集合复用已构建 loader（R8 下载/类加载只做一次）；
     * - 初始化失败抛 RuntimeException，由 ObfPlugin 转 GradleException 终止构建——
     *   宿主 R8 正是崩溃源，静默降级到宿主 classpath 等于必崩，没有兜底意义。
     */
    public static void initIsolated(Collection<File> r8Jars) {
        List<URL> jarUrls = new ArrayList<>();
        try {
            if (r8Jars != null) {
                for (File f : r8Jars) {
                    if (f != null && f.isFile()) {
                        jarUrls.add(f.toURI().toURL());
                    }
                }
            }
            // 核心库 jar（dex2jar/dexlib2 等）：复用插件 classpath 上已解析的依赖，隔离配置里无需重复声明坐标
            for (File f : collectPluginClasspathJars()) {
                URL u = f.toURI().toURL();
                if (!jarUrls.contains(u)) {
                    jarUrls.add(u);
                }
            }
        } catch (MalformedURLException e) {
            throw new RuntimeException("BlackObfuscator: invalid isolated jar URL", e);
        }
        // 插件自身 jar：ObfDexIsolatedHelper 类所在，须由隔离 CL 一并加载
        ProtectionDomain selfPd = ObfDex.class.getProtectionDomain();
        URL self = selfPd != null && selfPd.getCodeSource() != null
                ? selfPd.getCodeSource().getLocation() : null;
        if (self != null) {
            jarUrls.add(self);
        }
        // jar 集合未变则复用已构建的 loader（多 module / daemon 复用场景避免重复加载 R8）
        String loaderKey = buildLoaderKey(jarUrls);
        ClassLoader cachedLoader = isolatedLoader;
        if (cachedLoader != null && loaderKey.equals(isolatedLoaderKey)) {
            return;
        }
        // 多 module 并行配置阶段可能同时进入：double-checked 加锁，只构建一个 loader
        synchronized (ObfDex.class) {
            cachedLoader = isolatedLoader;
            if (cachedLoader != null && loaderKey.equals(isolatedLoaderKey)) {
                return;
            }
            try {
                ClassLoader loader = new ChildFirstURLClassLoader(
                        jarUrls.toArray(new URL[0]), ObfDex.class.getClassLoader());
                Class<?> d8 = loader.loadClass("com.android.tools.r8.D8");
                System.out.println("BlackObf[diag]: isolated D8 = "
                        + d8.getProtectionDomain().getCodeSource().getLocation());
                isolatedLoader = loader;
                isolatedLoaderKey = loaderKey;
            } catch (Throwable t) {
                throw new RuntimeException("BlackObfuscator: isolated R8 classloader init failed", t);
            }
        }
    }

    /**
     * jar 集合签名：URL + 文件大小 + 最后修改时间。
     * 覆盖同一坐标（同路径同名 jar 内容变化）时签名随之变化，强制重建 loader；
     * 排序后拼接保证确定性（URL 收集顺序无关）。
     */
    private static String buildLoaderKey(List<URL> jarUrls) {
        List<String> signatures = new ArrayList<>();
        for (URL u : jarUrls) {
            String sig = u.toExternalForm();
            try {
                File f = new File(u.toURI());
                sig = sig + "#" + f.length() + "#" + f.lastModified();
            } catch (Exception ignored) {
            }
            signatures.add(sig);
        }
        Collections.sort(signatures);
        StringBuilder keyBuilder = new StringBuilder();
        for (String signature : signatures) {
            keyBuilder.append(signature).append('\n');
        }
        return keyBuilder.toString();
    }

    /**
     * 收集插件 classpath 上的 jar：优先枚举 classloader URLs（全量）；
     * 非 URLClassLoader 场景用核心库关键类定位兜底（dexlib2 / dex-tools / dex-obfuscator 等）。
     * 全量收集无副作用：child-first 只加载实际被引用的类，且指定版本 R8 jar 排在 URL 前列优先命中。
     */
    static List<File> collectPluginClasspathJars() {
        List<File> jars = new ArrayList<>();
        ClassLoader cl = ObfDex.class.getClassLoader();
        if (cl instanceof URLClassLoader) {
            for (URL u : ((URLClassLoader) cl).getURLs()) {
                try {
                    File f = new File(u.toURI());
                    if (f.isFile() && f.getName().endsWith(".jar")) {
                        jars.add(f);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        addJarOf(jars, org.jf.DexLib2Utils.class);                                    // dexlib2
        addJarOf(jars, com.googlecode.dex2jar.tools.Dex2jarCmd.class);                // dex-tools
        addJarOf(jars, top.niunaijun.obfuscator.ObfuscatorConfiguration.class);       // dex-obfuscator
        addJarOf(jars, com.googlecode.d2j.reader.DexFileReader.class);                // dex-reader
        addJarOf(jars, com.googlecode.dex2jar.ir.IrMethod.class);                    // dex-ir
        addJarOf(jars, com.googlecode.dex2jar.tools.BaseCmd.class);                   // d2j-base-cmd
        return jars;
    }

    private static void addJarOf(List<File> jars, Class<?> c) {
        try {
            ProtectionDomain protectionDomain = c.getProtectionDomain();
            if (protectionDomain != null && protectionDomain.getCodeSource() != null
                    && protectionDomain.getCodeSource().getLocation() != null) {
                File f = new File(protectionDomain.getCodeSource().getLocation().toURI());
                if (f.isFile() && f.getName().endsWith(".jar") && !jars.contains(f)) {
                    jars.add(f);
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * child-first 类加载：隔离 classpath 内的类优先于 parent（Gradle/宿主）加载，
     * 保证 com.android.tools.r8.* 使用扩展指定的版本（默认 8.3.37），与宿主 R8 完全隔离。
     * 隔离 classpath 外的类（java.*、Gradle API 等）正常委托 parent。
     */
    static class ChildFirstURLClassLoader extends URLClassLoader {
        ChildFirstURLClassLoader(URL[] jarUrls, ClassLoader parent) {
            super(jarUrls, parent);
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) {
                    try {
                        c = findClass(name);
                    } catch (ClassNotFoundException e) {
                        c = super.loadClass(name, resolve);
                        return c;
                    }
                }
                if (resolve) {
                    resolveClass(c);
                }
                return c;
            }
        }
    }

    /**
     * 读取输入 dex 的版本（magic 第 5-7 字节，如 "035"/"037"/"039"），换算成 D8 的 min-api，
     * 使混淆写回的 dex 保持与原 dex 相同的版本（与 HeaderItem magic 映射一致：
     * <24→035、24-25→037、26-27→038、>=28→039）。
     */
    private static int getMinApiForDex(File input) {
        try (InputStream in = new FileInputStream(input)) {
            byte[] magic = new byte[8];
            int n = in.read(magic);
            if (n >= 7 && magic[0] == 'd' && magic[1] == 'e' && magic[2] == 'x' && magic[3] == '\n') {
                int version = Integer.parseInt(new String(magic, 4, 3, StandardCharsets.US_ASCII));
                if (version <= 36) return 21;   // 035/036
                if (version == 37) return 24;   // 037
                if (version == 38) return 26;   // 038
                return 28;                      // 039+
            }
        } catch (Exception ignored) {
        }
        return 21; // 读取失败时用默认
    }

    public static void obf(String dir, int depth, String[] obfClass, String[] blackClass, String mappingFile, String androidJar) {
        File file = new File(dir);
        Mapping mapping = new Mapping(mappingFile);
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files == null)
                return;
            for (File input : files) {
                if (input.isFile()) {
                    handleDex(input, depth, obfClass, blackClass, mapping, androidJar);
                } else {
                    obf(input.getAbsolutePath(), depth, obfClass, blackClass, mappingFile, androidJar);
                }
            }
        } else {
            handleDex(file, depth, obfClass, blackClass, mapping, androidJar);
        }
    }

    private static void handleDex(File input, int depth, String[] obfClass, String[] blackClass, Mapping mapping, String androidJar) {
        if (!input.getAbsolutePath().endsWith(".dex"))
            return;
        File tempJar = null;
        File splitDex = null;
        File obfDex = null;
        try {
            tempJar = new File(input.getParent(), System.currentTimeMillis() + "obf" + input.getName() + ".jar");
            splitDex = new File(input.getParent(), System.currentTimeMillis() + "split" + input.getName() + ".dex");
            obfDex = new File(input.getParent(), System.currentTimeMillis() + "obf" + input.getName() + ".dex");
            List<String> obfClassList = arrayToList(obfClass);
            List<String> blackClassList = arrayToList(blackClass);

            TrieTree whiteListTree = new TrieTree();
            whiteListTree.addAll(obfClassList);

            for (String renamedClass : mapping.getMapping().keySet()) {
                if (whiteListTree.search(renamedClass)) {
                    String orig = mapping.get(renamedClass);
                    if (orig != null) {
                        System.out.println("mapping : " + renamedClass + " ---> " + orig);
                        obfClassList.add(orig);
                    }
                }
            }

            TrieTree blackListTree = new TrieTree();
            blackListTree.addAll(blackClassList);
            List<String> tmpBlackClass = new ArrayList<>(blackClassList);
            for (String renamedClass : tmpBlackClass) {
                if (blackListTree.search(renamedClass)) {
                    String orig = mapping.get(renamedClass);
                    if (orig != null) {
                        System.out.println("mapping : " + renamedClass + " ---> " + orig);
                        blackClassList.add(orig);
                    }
                }
            }
            long splitCount = DexLib2Utils.splitDex(input, splitDex, obfClassList, blackClassList);
            if (splitCount <= 0) {
                System.out.println("Obfuscator Class not found");
                return;
            }

            // 混淆 + jar→dex 写回：整体反射进隔离 classloader（ObfDexIsolatedHelper），
            // 保证 Dex2jarCmd / Jar2Dex / ObfuscatorConfiguration 全部来自隔离类域。
            ClassLoader cl = isolatedLoader;
            if (cl == null) {
                // 隔离 loader 未初始化：宿主 R8 正是崩溃源，禁止静默降级到宿主 classpath
                throw new IllegalStateException(
                        "BlackObfuscator: isolated loader not initialized (call ObfDex.initIsolated first)");
            }
            // 保持 dex 版本：按输入 dex 的 magic 版本换算 min-api，混淆写回后版本不变
            int minApi = getMinApiForDex(input);
            Class<?> helper = Class.forName(
                    "top.niunaijun.blackobfuscator.core.ObfDexIsolatedHelper", true, cl);
            helper.getMethod("obfAndWriteBack", String.class, String.class, String.class, String.class, int.class, int.class)
                    .invoke(null,
                            splitDex.getAbsolutePath(),
                            tempJar.getAbsolutePath(),
                            obfDex.getAbsolutePath(),
                            androidJar,
                            depth,
                            minApi);
            DexLib2Utils.mergerAndCoverDexFile(input, obfDex, input);
        } catch (Throwable t) {
            // 明确报错而非静默：混淆失败若被吞掉，构建仍会成功但 dex 实际未混淆
            System.err.println("BlackObfuscator[ERROR]: obfuscate dex failed: " + input.getAbsolutePath());
            t.printStackTrace();
        } finally {
            tempJar.delete();
            splitDex.delete();
            obfDex.delete();
        }
    }

    private static List<String> arrayToList(String[] array) {
        List<String> list = Arrays.asList(array);
        return new ArrayList<>(list);
    }
}

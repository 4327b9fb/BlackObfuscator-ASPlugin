package top.niunaijun.blackobfuscator.core;

import com.googlecode.dex2jar.tools.Dex2jarCmd;
import com.googlecode.dex2jar.tools.Jar2Dex;

import top.niunaijun.obfuscator.ObfuscatorConfiguration;

/**
 * 隔离类域内的混淆执行入口：由 ObfDex 反射调用。
 * Dex2jarCmd / Jar2Dex / ObfuscatorConfiguration 全部由隔离 classloader
 * （child-first + 扩展指定版本 R8，默认 8.3.37）加载，规避宿主 R8 对混淆后
 * 控制流处理的崩溃（实测 AGP 9.2.x 宿主 R8 9.2.14 的 asDexWritableCode bug；
 * 其余 AGP 未逐一验证，统一隔离以防御未知风险）。
 */
public class ObfDexIsolatedHelper {

    public static void obfAndWriteBack(String splitDex, String tempJar, String obfDex, String androidJar, int depth, int minApi) throws Exception {
        new Dex2jarCmd(new ObfuscatorConfiguration() {
            @Override
            public int getObfDepth() {
                return depth;
            }

            @Override
            public boolean accept(String className, String methodName) {
                System.out.println("BlackObf Class: " + className + "#" + methodName);
                return super.accept(className, methodName);
            }
        }).doMain("-f", splitDex, "-o", tempJar);
        if (androidJar != null && !androidJar.isEmpty()) {
            new Jar2Dex().doMain("-f", "-l", androidJar, "--min-api", String.valueOf(minApi), "-o", obfDex, tempJar);
        } else {
            new Jar2Dex().doMain("-f", "--min-api", String.valueOf(minApi), "-o", obfDex, tempJar);
        }
    }
}

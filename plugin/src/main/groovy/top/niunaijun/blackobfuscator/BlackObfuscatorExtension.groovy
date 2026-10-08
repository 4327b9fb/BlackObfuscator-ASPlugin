package top.niunaijun.blackobfuscator
import org.gradle.api.Project

class BlackObfuscatorExtension {
    boolean enabled = false
    int depth = 1
    String[] obfClass = []
    String[] blackClass = []
    /** 隔离 R8 版本号；空/默认时回退 8.3.37（实测 AGP 9.2.x 宿主 R8 9.2.14 的 asDexWritableCode 崩溃） */
    String r8Version = '8.3.37'

    BlackObfuscatorExtension(Project project) {

    }


    @Override
    public String toString() {
        return "BlackObfuscatorExtension{" +
                "enabled=" + enabled +
                ", depth=" + depth +
                ", obfClass=" + Arrays.toString(obfClass) +
                ", blackClass=" + Arrays.toString(blackClass) +
                ", r8Version='" + r8Version + '\'' +
                '}';
    }
}
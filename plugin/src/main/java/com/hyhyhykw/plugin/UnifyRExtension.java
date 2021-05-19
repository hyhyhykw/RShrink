package com.hyhyhykw.plugin;

import java.util.List;

/**
 * Created time : 2021/5/12 13:23.
 *
 * @author 10585
 */
public class UnifyRExtension {
    // app 模块包名
    public String packageName;
    // 类白名单包名，处于此包下的类不处理
    public List<String> whitePackage;
    // debug模式下跳过处理
    public boolean skipDebug = true;
}
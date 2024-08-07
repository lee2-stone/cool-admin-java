package com.cool.core.plugin.service;

import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.ReflectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.cool.core.config.PluginJson;
import com.cool.core.exception.CoolPreconditions;
import com.cool.core.plugin.config.DynamicJarClassLoader;
import com.cool.core.util.AnnotationUtils;
import com.cool.modules.plugin.entity.PluginInfoEntity;
import com.cool.modules.plugin.service.PluginInfoService;
import java.io.File;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 动态加载jar包
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicJarLoaderService {

    final private PluginInfoService pluginInfoService;

    private final Map<String, DynamicJarClassLoader> dynamicJarClassLoaderMap = new ConcurrentHashMap<>();

    private final Map<String, Object> pluginMap = new ConcurrentHashMap<>();

    public PluginJson install(String jarFilePath, boolean force) throws Exception {
        URL jarUrl = new URL("jar:file:" + new File(jarFilePath).getAbsolutePath() + "!/");
        DynamicJarClassLoader dynamicJarClassLoader = new DynamicJarClassLoader(new URL[]{jarUrl},
            Thread.currentThread().getContextClassLoader());
        Thread.currentThread().setContextClassLoader(dynamicJarClassLoader);
        InputStream inputStream = dynamicJarClassLoader.getResourceAsStream("plugin.json");
        CoolPreconditions.check(ObjUtil.isEmpty(inputStream), "不合规插件：未找到plugin.json文件");
        String pluginJsonStr = StrUtil.str(IoUtil.readBytes(inputStream), "UTF-8");
        PluginJson pluginJson = JSONUtil.toBean(pluginJsonStr, PluginJson.class);
        CoolPreconditions.check(ObjUtil.isEmpty(pluginJson.getKey()), "该插件缺少唯一标识");
        if (ObjUtil.isNotEmpty(pluginJson.getHook())) {
            // 查找hook是否已经存在,提示是否要替换，原hook将关闭
            PluginInfoEntity pluginInfoEntity = pluginInfoService
                .getPluginInfoEntityByHookNoJarFile(pluginJson.getHook());
            if (!force) {
                CoolPreconditions.returnData(
                    ObjUtil.isNotEmpty(pluginInfoEntity)
                        && !ObjUtil.equals(pluginInfoEntity.getKey(), pluginJson.getKey()),
                    new CoolPreconditions.ReturnData(1,
                        "插件已存在相同hook: {}，继续安装将关闭原来插件（同名hook，只能有一个状态开启）",
                        pluginJson.getHook()));

            } else if (ObjUtil.isNotEmpty(pluginInfoEntity)
                && !ObjUtil.equals(pluginInfoEntity.getKey(), pluginJson.getKey())) {
                // 存在同名hook，需将原hook修改为关闭
                pluginJson.setSameHookId(pluginInfoEntity.getId());
            }
        }

        // 加载类
        List<Class<?>> plugins = new ArrayList<>();
        try (JarFile jarFile = ((JarURLConnection) jarUrl.openConnection()).getJarFile()) {
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry jarEntry = entries.nextElement();
                if (jarEntry.getName().endsWith(".class")) {
                    String className = jarEntry.getName().replace('/', '.').substring(0,
                        jarEntry.getName().length() - 6);
                    try {
                        // 加载类
                        Class<?> clazz = dynamicJarClassLoader.loadClass(className);
                        if (AnnotationUtils.hasCoolPluginAnnotation(clazz)) {
                            plugins.add(clazz);
                        }
                    } catch (NoClassDefFoundError ignored) {
                    }
                }
            }
        }

        // 校验插件
        checkPlugin(plugins);
        registerPlugin(pluginJson.getKey(), plugins.get(0), dynamicJarClassLoader, force);
        dynamicJarClassLoaderMap.put(pluginJson.getKey(), dynamicJarClassLoader);

        log.info("插件{}初始化成功.", pluginJson.getKey());
        return pluginJson;
    }

    /**
     * 注册插件
     */
    private void registerPlugin(String key, Class<?> pluginClazz,
        DynamicJarClassLoader dynamicJarClassLoader,
        boolean force) {
        if (!force && pluginMap.containsKey(key)) {
            dynamicJarClassLoader.unload();
            CoolPreconditions.returnData(
                new CoolPreconditions.ReturnData(1, "插件已存在，继续安装将覆盖"));
        }
        if (ObjUtil.isNotEmpty(key)) {
            pluginMap.remove(key);
            pluginMap.put(key, ReflectUtil.newInstance(pluginClazz));
        }
    }

    /**
     * 卸载
     */
    public boolean uninstall(String key) {
        DynamicJarClassLoader dynamicJarClassLoader = getDynamicJarClassLoader(key);
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(dynamicJarClassLoader);
            pluginMap.remove(key);
            if (dynamicJarClassLoader != null) {
                dynamicJarClassLoader.unload();
            }
        } catch (Exception e) {
            log.error("uninstall {}失败", key, e);
            CoolPreconditions.alwaysThrow("卸载失败");
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }
        log.info("卸载插件{}", key);
        return true;
    }

    /**
     * 校验插件是否合法
     */
    private void checkPlugin(List<Class<?>> plugins) {
        CoolPreconditions.checkEmpty(plugins, "未找到插件程序");
        int size = plugins.size();
        CoolPreconditions.check(size == 0,
            "没找到符合规范的插件,插件需有@CoolPlugin注解，且以Plugin结尾(如：DemoPlugin)");
        CoolPreconditions.check(size > 1,
            "识别到当前安装包有多个插件(只能有一个@CoolPlugin注解)，一次只支持一个插件导入");
    }

    /**
     * 获取插件实例对象
     */
    public Object getBeanInstance(String key) {
        CoolPreconditions.checkEmpty(key, "插件key is null");
        if (pluginMap.containsKey(key)) {
            return pluginMap.get(key);
        }
        CoolPreconditions.alwaysThrow("插件 {} 未找到", key);
        return null;
    }

    /**
     * 获取自定义类加载器
     */
    public DynamicJarClassLoader getDynamicJarClassLoader(String key) {
        return dynamicJarClassLoaderMap.get(key);
    }
}

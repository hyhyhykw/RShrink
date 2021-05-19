package com.hyhyhykw.plugin;

import com.android.build.gradle.AppExtension;

import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

import javax.annotation.Nonnull;

/**
 * Created time : 2021/5/12 13:24.
 *
 * @author 10585
 */
public class KunioPlugin implements Plugin<Project> {
    private static final String CONFIG_NAME = "UnifyRExtension";

    @Override
    public void apply(@Nonnull Project project) {
        boolean hasAppPlugin = project.getPlugins().hasPlugin("com.android.application");
        if (!hasAppPlugin) {
            throw new GradleException("this plugin can't use in library module");
        }
        AppExtension android = (AppExtension) project.getExtensions().findByName("android");
        if (android == null) {
            throw new NullPointerException("application module not have \"android\" block!");
        }
        project.getExtensions().create(CONFIG_NAME, UnifyRExtension.class);
        android.registerTransform(new UnifyRTransform(project));
    }
}
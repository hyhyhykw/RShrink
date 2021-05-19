package com.hyhyhykw.plugin;

import com.android.build.api.transform.DirectoryInput;
import com.android.build.api.transform.Format;
import com.android.build.api.transform.JarInput;
import com.android.build.api.transform.QualifiedContent;
import com.android.build.api.transform.Status;
import com.android.build.api.transform.Transform;
import com.android.build.api.transform.TransformException;
import com.android.build.api.transform.TransformInput;
import com.android.build.api.transform.TransformInvocation;
import com.android.build.api.transform.TransformOutputProvider;
import com.android.build.gradle.internal.pipeline.TransformManager;
import com.android.ide.common.internal.WaitableExecutor;
import com.android.utils.FileUtils;

import org.apache.commons.io.IOUtils;
import org.gradle.api.Project;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

/**
 * Created time : 2021/5/12 13:25.
 *
 * @author 10585
 */
public class UnifyRTransform extends Transform {
    private static final List<String> R = Arrays.asList(
            "R$xml",
            "R$transition",
            "R$styleable",
            "R$style",
            "R$string",
            "R$raw",
            "R$plurals",
            "R$mipmap",
            "R$menu",
            "R$layout",
            "R$interpolator",
            "R$integer",
            "R$id",
            "R$fraction",
            "R$font",
            "R$drawable",
            "R$dimen",
            "R$color",
            "R$bool",
            "R$attr",
            "R$array",
            "R$animator",
            "R$anim");

    @SuppressWarnings("deprecation")
    private WaitableExecutor executor = WaitableExecutor.useGlobalSharedThreadPool();
    private static final String NAME = "UnifyR";
    private final Project project;
    private String appPackagePrefix;
    private List<String> whitePackages;

    public UnifyRTransform(Project project) {
        this.project = project;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public Set<QualifiedContent.ContentType> getInputTypes() {
        return TransformManager.CONTENT_CLASS;
    }

    @Override
    public Set<? super QualifiedContent.Scope> getScopes() {
        return TransformManager.SCOPE_FULL_PROJECT;
    }

    @Override
    public boolean isIncremental() {
        return true;
    }

    @Override
    public void transform(TransformInvocation transformInvocation) throws TransformException, InterruptedException, IOException {
        UnifyRExtension unifyRExtension = project.getExtensions().findByType(UnifyRExtension.class);
        boolean skipDebugUnifyR = transformInvocation.getContext().getVariantName().toLowerCase().contains("debug") && unifyRExtension.skipDebug;
        if (skipDebugUnifyR) {
            copyOnly(transformInvocation.getInputs(), transformInvocation.getOutputProvider());
            return;
        }
        appPackagePrefix = unifyRExtension.packageName.replace('.', '/') + '/';
        List<String> whitePackage = unifyRExtension.whitePackage;
        List<String> whites = new ArrayList<>();
        if (whitePackage != null) {
            for (String s : whitePackage) {
                whites.add(s.replace('.', '/'));
            }
        }
        whitePackages = new ArrayList<>(whites);

        boolean isIncremental = transformInvocation.isIncremental();
        TransformOutputProvider outputProvider = transformInvocation.getOutputProvider();
        if (!isIncremental) {
            outputProvider.deleteAll();
        }
        Collection<TransformInput> inputs = transformInvocation.getInputs();
        for (TransformInput input : inputs) {
            Collection<JarInput> jarInputs = input.getJarInputs();
            for (JarInput jarInput : jarInputs) {
                executor.execute(() -> {
                    processJarInputWithIncremental(jarInput, outputProvider, isIncremental);
                    return null;
                });
            }

            Collection<DirectoryInput> directoryInputs = input.getDirectoryInputs();
            for (DirectoryInput directoryInput : directoryInputs) {
                executor.execute(() -> {
                    processDirectoryInputWithIncremental(directoryInput, outputProvider, isIncremental);
                    return null;
                });
            }
        }
        executor.waitForTasksWithQuickFail(true);
    }

    private void processDirectoryInputWithIncremental(DirectoryInput directoryInput, TransformOutputProvider outputProvider, boolean isIncremental) throws IOException {
        File inputDir = directoryInput.getFile();
        File outputDir = outputProvider.getContentLocation(
                directoryInput.getName(),
                directoryInput.getContentTypes(),
                directoryInput.getScopes(),
                Format.DIRECTORY);
        if (isIncremental) {
            Set<Map.Entry<File, Status>> entries = directoryInput.getChangedFiles().entrySet();
            for (Map.Entry<File, Status> entry : entries) {
                File file = entry.getKey();
                File destFile = new File(file.getAbsolutePath().replace(inputDir.getAbsolutePath(), outputDir.getAbsolutePath()));
                Status status = entry.getValue();
                switch (status) {
                    case ADDED:
                        FileUtils.mkdirs(destFile);
                        FileUtils.copyFile(file, destFile);
                        break;
                    case CHANGED:
                        FileUtils.deleteIfExists(destFile);
                        FileUtils.mkdirs(destFile);
                        FileUtils.copyFile(file, destFile);
                        break;
                    case REMOVED:
                        FileUtils.deleteIfExists(destFile);
                        break;
                    case NOTCHANGED:
                        break;
                }
            }
        } else {
            FileUtils.copyDirectory(directoryInput.getFile(), outputDir);
        }
    }

    private void processJarInputWithIncremental(JarInput jarInput, TransformOutputProvider outputProvider, boolean isIncremental) throws IOException {
        File dest = outputProvider.getContentLocation(
                jarInput.getFile().getAbsolutePath(),
                jarInput.getContentTypes(),
                jarInput.getScopes(),
                Format.JAR);
        if (isIncremental) {
            //处理增量编译
            switch (jarInput.getStatus()) {
                case NOTCHANGED:
                    break;
                case ADDED:
                    processJarInput(jarInput, dest);
                    break;
                case CHANGED:
                    //处理有变化的
                    FileUtils.deleteIfExists(dest);
                    processJarInput(jarInput, dest);
                    break;
                case REMOVED:
                    //移除Removed
                    if (dest.exists()) {
                        FileUtils.delete(dest);
                    }
                    break;
            }
        } else {
            //不处理增量编译
            processJarInput(jarInput, dest);
        }
    }

    private void processJarInput(JarInput jarInput, File dest) throws IOException {
        processClass(jarInput);
        FileUtils.copyFile(jarInput.getFile(), dest);
    }

    private void processClass(JarInput jarInput) throws IOException {
        File file = jarInput.getFile();
        File tempJar = new File(file.getParentFile(), file.getName() + ".temp");
        JarOutputStream jarOutputStream = new JarOutputStream(new FileOutputStream(tempJar));
        JarFile jar = new JarFile(file);
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            byte[] destBytes = null;
            JarEntry jarEntry = entries.nextElement();
            InputStream inputStream = jar.getInputStream(jarEntry);
            String name = jarEntry.getName();
            if (name.endsWith(".class")) {
                boolean keep = false;
                for (String s : whitePackages) {
                    if (name.contains(s)) {
                        keep = true;
                        break;
                    }
                }
                if (keep) {
                    destBytes = IOUtils.toByteArray(inputStream);
                } else {
                    if (!hasR(name)) {
                        destBytes = unifyR(name, inputStream);
                    } else if (name.startsWith(appPackagePrefix)) {
                        destBytes = IOUtils.toByteArray(inputStream);
                    }
                }
            } else {
                destBytes = IOUtils.toByteArray(inputStream);
            }
            if (destBytes != null) {
                jarOutputStream.putNextEntry(new ZipEntry(jarEntry.getName()));
                jarOutputStream.write(destBytes);
                jarOutputStream.closeEntry();
            }
            inputStream.close();
        }
        jar.close();
        jarOutputStream.close();
        FileUtils.delete(file);
        tempJar.renameTo(file);
    }

    private byte[] unifyR(String entryName, InputStream inputStream) throws IOException {
        ClassReader cr = new ClassReader(inputStream);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        ClassVisitor cv = new ClassVisitor(Opcodes.ASM6, cw) {

            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                                             String signature, String[] exceptions) {
                MethodVisitor mv = cv.visitMethod(access, name, desc, signature, exceptions);
                return new MethodVisitor(Opcodes.ASM6, mv) {
                    @Override
                    public void visitFieldInsn(int opcode, String owner, String fName, String fDesc) {
                        if (hasR(owner) && !owner.contains(appPackagePrefix)) {
                            super.visitFieldInsn(opcode, appPackagePrefix + "R$" + owner.substring(owner.indexOf("R$") + 2), fName, fDesc);
                        } else {
                            super.visitFieldInsn(opcode, owner, fName, fDesc);
                        }
                    }
                };
            }

        };
        cr.accept(cv, ClassReader.EXPAND_FRAMES);
        return cw.toByteArray();
    }

    private static void copyOnly(Collection<TransformInput> inputs, TransformOutputProvider outputProvider) throws IOException {
        for (TransformInput input : inputs) {
            Collection<JarInput> jarInputs = input.getJarInputs();
            for (JarInput jarInput : jarInputs) {
                File dest = outputProvider.getContentLocation(
                        jarInput.getName(),
                        jarInput.getContentTypes(),
                        jarInput.getScopes(),
                        Format.JAR);
                FileUtils.copyFile(jarInput.getFile(), dest);
            }

            Collection<DirectoryInput> directoryInputs = input.getDirectoryInputs();
            for (DirectoryInput directoryInput : directoryInputs) {
                File dest = outputProvider.getContentLocation(
                        directoryInput.getName(),
                        directoryInput.getContentTypes(),
                        directoryInput.getScopes(),
                        Format.DIRECTORY);
                FileUtils.copyDirectory(directoryInput.getFile(), dest);
            }
        }
    }

    /**
     * 判断这个字符串里面有没有R文件的标识
     *
     * @param check 待检测的字符串
     * @return 有标识的话返回true
     */
    private static boolean hasR(String check) {
        for (String s : R) {
            if (check.contains(s)) {
                return true;
            }
        }
        return false;
    }
}
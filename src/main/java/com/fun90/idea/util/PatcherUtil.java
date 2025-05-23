package com.fun90.idea.util;

import com.intellij.ide.projectView.impl.ProjectRootsUtil;
import com.intellij.notification.*;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.compiler.CompileContext;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VirtualFile;

import javax.swing.*;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PatcherUtil {
    private static final String PLUGIN_NAME = "PatcherX";
    private static final String NOTIFICATION_TITLE = "PatcherX";
    private static final NotificationGroup NOTIFICATION_GROUP = new NotificationGroup(PLUGIN_NAME + " log",
            NotificationDisplayType.BALLOON, true);
    private static final Pattern webPathPattern = Pattern.compile("(.+)/(webapp|WebRoot)/(.+)");

    private static final Set<String> filterCompileFiles = new HashSet<>(Arrays.asList("custom-actionModels.xml", "custom-actions.xml", "custom.xml", "mvc.xml"));

    public static PathResult getPathResult(Module module, ListModel<VirtualFile> selectedFiles, String pathPrefix, CompileContext compileContext) {
        Project project = module.getProject();
        // 源码目录
        ModuleRootManager moduleRootManager = ModuleRootManager.getInstance(module);
        VirtualFile[] sourceRoots = moduleRootManager.getSourceRoots();
        List<String> sourceRootPathList = new ArrayList<>(sourceRoots.length);
        for (VirtualFile sourceRoot : sourceRoots) {
            sourceRootPathList.add(sourceRoot.getPath());
        }
        PathResult pathResult = new PathResult();
        String contentRoot = moduleRootManager.getContentRoots()[0].getPath();
        for (int i = 0; i < selectedFiles.getSize(); i++) {
            VirtualFile element = selectedFiles.getElementAt(i);
            String elementName = element.getName();
            String elementPath = element.getPath();
            String fileType = element.getFileType().getName();

            if (compileContext != null) {
                String sourceRootPath = getSourceRootPath(sourceRootPathList, elementPath);
                if (sourceRootPath != null && !ProjectRootsUtil.isInTestSource(element, project)) {
                    // 编译输出目录
                    VirtualFile compilerOutputPath = compileContext.getModuleOutputDirectory(module);
                    if (compilerOutputPath == null) {
                        showInfo("The module (" + module.getName() + ") has no output directory!", project);
                        return new PathResult();
                    }
                    String compilerOutputUrl = compilerOutputPath.getPath();
                    String outName = elementPath.split(sourceRootPath)[1];
                    if ("java".equalsIgnoreCase(fileType)) {
                        outName = outName.replace("java", "");
                        String className = elementName.replace(".java", "");
                        if (!className.startsWith("_")) { // 如果是_开头的java文件，是无法编译copy的，直接按项目结构放源文件
                            String packageDir = outName.substring(0, outName.lastIndexOf("/") + 1);
                            String classLocation = compilerOutputUrl + packageDir;
                            // 针对一个Java文件编译出多个class文件的情况，如:Test$1.class
                            List<Path> fileList = FilesUtil.matchFiles("glob:**" + File.separator + className + "$*.class", classLocation);
                            // 添加本身class文件
                            fileList.add(Paths.get(classLocation + className + ".class"));
                            for (Path from : fileList) {
                                String toName = packageDir + from.getFileName().toString();
                                Path to = Paths.get(pathPrefix + "codebase" + toName);
                                pathResult.put(from, to);
                            }
                        }
                    } else if ("xml".equalsIgnoreCase(fileType)){ // 针对mybatis映射文件mapper文件做处理，将其也导出到codebase中
                        String className = elementName.replace(".xml", "");

                        String packageDir = outName.substring(0, outName.lastIndexOf("/") + 1);
                        String xmlLocation = compilerOutputUrl + packageDir;

                        Path classPath = Paths.get(xmlLocation + className + ".class"); // 如果这个文件存在class文件，那么说明这个是mapper映射文件

                        if (classPath.toFile().exists()) {
                            Path from = Paths.get(xmlLocation + elementName);
                            Path to = Paths.get(pathPrefix + "codebase" + outName);
                            pathResult.put(from, to);
                        }
                    }
                }

                if (filterCompileFiles.contains(elementName)) {
                    pathResult.addUnsettled(elementName);
                    continue;
                }
            }

            structure(pathPrefix, pathResult, contentRoot, elementPath); // 始终导出源文件，按项目结构

            //不清楚的功能
            /*Matcher webPathMatcher = webPathPattern.matcher(elementPath);
            if (webPathMatcher.find()) {
                Path from = Paths.get(elementPath);
                Path to = Paths.get(pathPrefix + webPathMatcher.group(3));
                pathResult.put(from, to);
            } else {
                pathResult.addUnsettled(elementPath);
            }*/
        }
        return pathResult;
    }

    /**
     * 按照项目结构放置源文件
     */
    private static void structure(String pathPrefix, PathResult pathResult, String contentRoot, String elementPath) {
        String[] tmp = elementPath.split(contentRoot);
        if (tmp.length == 0) {
            return;
        }
        String outName = tmp[1];
        Path from = Paths.get(elementPath);
        Path to = Paths.get(pathPrefix + File.separator + outName);
        pathResult.put(from, to);
    }

    public static Module getModule(Module[] modules, AnActionEvent event) {
        Map<String, Module> moduleMap = new HashMap<>();
        for (Module module : modules) {
            Optional<VirtualFile> moduleFile = Optional.ofNullable(module.getModuleFile());
            moduleFile.map(file -> file.getParent().getPath()).ifPresent(modulePath -> moduleMap.put(modulePath, module));
        }
        // 模块对象
        Module module = modules.length == 1 ? modules[0] : event.getData(LangDataKeys.MODULE);
        VirtualFile[] files = event.getData(LangDataKeys.VIRTUAL_FILE_ARRAY);
        if (module == null && !isNotSameModule(files)) {
            String moduleDirectoryPath = PatcherUtil.getModuleDirectoryPath(files);
            module = moduleMap.get(moduleDirectoryPath);
        }
        return module;
    }

    private static Pattern modulePattern = Pattern.compile("((.+)/(.+))/(src|WebRoot)/.*");

    public static boolean isNotSameModule(VirtualFile[] selectedFiles) {
        if (selectedFiles == null) {
            return false;
        }
        String moduleName = null;
        for (VirtualFile selectedFile : selectedFiles) {
            Matcher matcher = modulePattern.matcher(selectedFile.getPath());
            if (matcher.find()) {
                String newName = matcher.group(3);
                if (moduleName != null && !newName.equals(moduleName)) {
                    return true;
                }
                moduleName = newName;
            }
        }
        return false;
    }

    public static String getModuleDirectoryPath(VirtualFile[] selectedFiles) {
        if (selectedFiles == null) {
            return null;
        }
        for (VirtualFile selectedFile : selectedFiles) {
            Matcher matcher = modulePattern.matcher(selectedFile.getPath());
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    public static void showInfo(String content, Project project) {
        showNotification(content, NotificationType.INFORMATION, project);
    }

    public static void showError(String content, Project project) {
        showNotification(content, NotificationType.ERROR, project);
    }

    public static void showWarning(String content, Project project) {
        showNotification(content, NotificationType.WARNING, project);
    }

    private static void showNotification(String content, NotificationType type, Project project) {
        Notifications.Bus.notify(PatcherUtil.NOTIFICATION_GROUP.createNotification(
                PatcherUtil.NOTIFICATION_TITLE, content, type,
                NotificationListener.URL_OPENING_LISTENER), project);
    }

    private static String getSourceRootPath(List<String> sourceRootPathList, String elementPath) {
        for (String s : sourceRootPathList) {
            if (elementPath.contains(s)) {
                return s;
            }
        }
        return null;
    }

    public static void main(String[] args) {
        String s1 = "F:/code/trunk/hs-misc-service/miscservice-server/src/main/java/com/huasheng/misc/util/TradeDateUtil.java";
        String s2 = "miscservice-server";
        int i = s1.indexOf(s2);
        System.out.println(s1.substring(0, i - 1));
    }
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.nbandroid.netbeans.gradle.v2;

import com.android.builder.model.AndroidProject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.gradle.tooling.model.gradle.GradleBuild;
import org.nbandroid.netbeans.gradle.v2.gradle.FindAndroidVisitor;
import org.nbandroid.netbeans.gradle.v2.sdk.AndroidSdk;
import org.nbandroid.netbeans.gradle.v2.sdk.AndroidSdkImpl;
import org.nbandroid.netbeans.gradle.v2.sdk.AndroidSdkProvider;
import org.nbandroid.netbeans.gradle.v2.sdk.AndroidSdkTools;
import org.nbandroid.netbeans.gradle.v2.sdk.PlatformConvertor;
import org.nbandroid.netbeans.gradle.v2.sdk.ui.SdksCustomizer;
import org.netbeans.api.project.FileOwnerQuery;
import org.netbeans.api.project.Project;
import org.netbeans.gradle.project.NbGradleProject;
import org.netbeans.gradle.project.api.entry.GradleProjectExtension2;
import org.netbeans.gradle.project.api.entry.GradleProjectExtensionDef;
import org.netbeans.gradle.project.api.entry.ModelLoadResult;
import org.netbeans.gradle.project.api.entry.ParsedModel;
import org.netbeans.gradle.project.api.modelquery.GradleModelDefQuery1;
import org.netbeans.gradle.project.api.modelquery.GradleTarget;
import org.netbeans.gradle.project.java.JavaExtensionDef;
import org.netbeans.gradle.project.properties.global.CommonGlobalSettings;
import org.openide.DialogDisplayer;
import org.openide.NotifyDescriptor;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.util.Exceptions;
import org.openide.util.Lookup;
import org.openide.util.lookup.Lookups;
import org.openide.util.lookup.ServiceProvider;

/**
 *
 * @author arsi
 */
@ServiceProvider(service = GradleProjectExtensionDef.class, position = 900)
public class AndroidExtensionDef implements GradleProjectExtensionDef<SerializableLookup> {

    public static final String EXTENSION_NAME = "org.nbandroid.netbeans.gradle.v2.AndroidExtensionDef";
    public static final String LOCAL_PROPERTIES = "local.properties";
    public static final String GRADLE_PROPERTIES = "gradle.properties";
    public static final String BUILD_GRADLE = "build.gradle";
    public static final String SDK_DIR = "sdk.dir";
    public static final String COMMENT = "## This file is automatically generated by Apache Netbeans.\n"
            + "# Do not modify this file -- YOUR CHANGES WILL BE ERASED!\n"
            + "#\n"
            + "# This file must *NOT* be checked into Version Control Systems,\n"
            + "# as it contains information specific to your local configuration.\n"
            + "#\n"
            + "# Location of the SDK. This is only used by Gradle.\n"
            + "# For customization when using a Version Control System, please read the\n"
            + "# header note.\n"
            + "#DATE";

    private final Lookup lookup;

    public AndroidExtensionDef() {
        this.lookup = Lookups.fixed(new Query1(), new Query2());
    }

    @Override
    public String getName() {
        return EXTENSION_NAME;
    }

    @Override
    public String getDisplayName() {
        return "Android";
    }

    @Override
    public Lookup getLookup() {
        return lookup;
    }

    @Override
    public Class<SerializableLookup> getModelType() {
        return SerializableLookup.class;
    }

    @Override
    public ParsedModel<SerializableLookup> parseModel(ModelLoadResult retrievedModels) {
        return new ParsedModel<>(new SerializableLookup(retrievedModels.getMainProjectModels()));
    }

    @Override
    public GradleProjectExtension2<SerializableLookup> createExtension(Project project) throws IOException {
        boolean isAndroidProject = false;
        AndroidSdk defaultSdk = null;
        FileObject localProperties = null;
        FileObject buildScript = project.getProjectDirectory().getFileObject(BUILD_GRADLE);
        if (buildScript != null) {
            isAndroidProject = FindAndroidVisitor.visit(FileUtil.toFile(buildScript));
        }
        if (isAndroidProject) {
            localProperties = findAndroidLocalProperties(project.getProjectDirectory(), project);
            try {
                List<String> current = null;
                if (CommonGlobalSettings.getDefault().gradleArgs().getActiveValue() != null) {
                    current = new ArrayList<>(CommonGlobalSettings.getDefault().gradleArgs().getActiveValue());
                } else {
                    current = new ArrayList<>();
                }
                if (!current.contains("-Pandroid.injected.build.model.only.versioned=3")) {
                    current.add("-Pandroid.injected.build.model.only.versioned=3");
                }
                CommonGlobalSettings.getDefault().gradleArgs().setValue(current);
            } catch (Exception exception) {
            }
            if (localProperties == null) {
                Project rootProject = findRootProject(project.getProjectDirectory(), project);
                localProperties = rootProject.getProjectDirectory().createData(LOCAL_PROPERTIES);
            }
        }
        try {

            if (localProperties != null && isAndroidProject) {
                Properties properties = new Properties();
                properties.load(localProperties.getInputStream());
                final String sdkDir = properties.getProperty(SDK_DIR, null);
                if (sdkDir == null || !AndroidSdkTools.isSdkFolder(new File(sdkDir))) {
                    //no local.properties
                    //no default SDK
                    defaultSdk = handleDefaultSdk(project, properties, localProperties);
                } else {
                    //we have valid SDK folder
                    defaultSdk = AndroidSdkProvider.findSdk(new File(sdkDir));
                    if (defaultSdk == null) {
                        NotifyDescriptor nd = new NotifyDescriptor.Confirmation("<html>Project " + ((NbGradleProject) project).getDisplayName() + " contains a SDK that is not defined in the IDE.<br>Do you want to add this SDK?<br>When you choose No, the default SDK will be used.</html>", "Android SDK import", NotifyDescriptor.YES_NO_OPTION, NotifyDescriptor.QUESTION_MESSAGE);
                        Object notify = DialogDisplayer.getDefault().notify(nd);
                        if (NotifyDescriptor.YES_OPTION.equals(notify)) {
                            NotifyDescriptor.InputLine nd1 = new NotifyDescriptor.InputLine("Please enter the name of SDK", "Android SDK import", NotifyDescriptor.OK_CANCEL_OPTION, NotifyDescriptor.QUESTION_MESSAGE);
                            Object notify1;
                            String name = null;
                            do {
                                notify1 = DialogDisplayer.getDefault().notify(nd1);
                                name = nd1.getInputText();
                            } while (!NotifyDescriptor.OK_OPTION.equals(notify1) && name != null && !name.isEmpty());
                            final String sdkName = name;
                            //this thread holds ProjectManager.mutex().read must be called outside
                            Callable<AndroidSdk> run = new Callable<AndroidSdk>() {
                                @Override
                                public AndroidSdk call() throws Exception {
                                    AndroidSdkImpl defaultSdk = new AndroidSdkImpl(sdkName, sdkDir);
                                    PlatformConvertor.create(defaultSdk);
                                    return defaultSdk;
                                }
                            };
                            Future<AndroidSdk> submit = Executors.newSingleThreadExecutor().submit(run);
                            try {
                                defaultSdk = submit.get();
                            } catch (InterruptedException | ExecutionException ex) {
                                Exceptions.printStackTrace(ex);
                            }
                            if (defaultSdk != null) {
                                storeLocalProperties(properties, defaultSdk, localProperties);
                            }
                        } else {
                            //use default SDK
                            defaultSdk = handleDefaultSdk(project, properties, localProperties);
                        }
                    }
                    //SDK from local.properties is OK continue
                }

            }
        } catch (IOException iOException) {
        }
        return new AndroidGradleExtensionV2(project, defaultSdk, localProperties);
    }

    public AndroidSdk handleDefaultSdk(Project project, Properties properties, FileObject localProperties) {
        AndroidSdk defaultSdk = AndroidSdkProvider.getDefaultSdk();
        if (defaultSdk == null || defaultSdk.isBroken()) {//no default SDK
            NotifyDescriptor nd2 = null;
            if (defaultSdk == null) {
                nd2 = new NotifyDescriptor.Confirmation("<html>To open "
                        + ((NbGradleProject) project).getDisplayName()
                        + " Project, you need to define an Android SDK location!<br>Do you want to set or download the android SDK?"
                        + "</html>", "Android SDK location", NotifyDescriptor.YES_NO_OPTION, NotifyDescriptor.WARNING_MESSAGE);
                Object notify2 = DialogDisplayer.getDefault().notify(nd2);
                if (NotifyDescriptor.YES_OPTION.equals(notify2)) {
                    SdksCustomizer.showCustomizer();
                    defaultSdk = AndroidSdkProvider.getDefaultSdk();
                    if (defaultSdk != null) {
                        storeLocalProperties(properties, defaultSdk, localProperties);
                        return defaultSdk;
                    }
                }
            } else {
                nd2 = new NotifyDescriptor.Confirmation("<html><b>Broken Android SDK!</b><br>"
                        + "To open " + ((NbGradleProject) project).getDisplayName()
                        + " Project, you need to define an Android SDK location!<br>Do you want to set or download the android SDK?"
                        + "</html>", "Android SDK location", NotifyDescriptor.YES_NO_OPTION, NotifyDescriptor.WARNING_MESSAGE);
                Object notify2 = DialogDisplayer.getDefault().notify(nd2);
                if (NotifyDescriptor.YES_OPTION.equals(notify2)) {
                    SdksCustomizer.showCustomizer();
                    if (defaultSdk.isValid()) {
                        storeLocalProperties(properties, defaultSdk, localProperties);
                        return defaultSdk;
                    }
                }
            }

        } else {
            storeLocalProperties(properties, defaultSdk, localProperties);
            return defaultSdk;
        }
        return null;
    }

    public void storeLocalProperties(Properties properties, AndroidSdk defaultPlatform, FileObject localProperties) {
        FileOutputStream fo = null;
        try {
            //have default SDK write to properties
            properties.setProperty(SDK_DIR, defaultPlatform.getInstallFolder().getPath());
            fo = new FileOutputStream(FileUtil.toFile(localProperties));
            try {
                properties.store(fo, COMMENT.replace("#DATE", new Date().toString()));
            } finally {
                try {
                    fo.close();
                } catch (IOException iOException) {
                }
            }
        } catch (IOException ex) {
            Exceptions.printStackTrace(ex);
        } finally {
            try {
                fo.close();
            } catch (IOException ex) {
                Exceptions.printStackTrace(ex);
            }
        }
    }

    private FileObject findAndroidLocalProperties(FileObject root, Project current) {
        FileObject fo = root.getFileObject("local.properties");
        if (fo != null) {
            return fo;
        } else {
            Project owner = FileOwnerQuery.getOwner(root.getParent());
            if ((owner instanceof NbGradleProject) && !owner.equals(current)) {
                return findAndroidLocalProperties(root.getParent(), current);
            } else {
                return null;
            }
        }
    }

    private Project findRootProject(FileObject root, Project current) {
        Project owner = FileOwnerQuery.getOwner(root.getParent());
        if ((owner instanceof NbGradleProject) && !owner.equals(current)) {
            return findRootProject(root.getParent(), owner);
        } else {
            return current;
        }
    }

    @Override
    public Set<String> getSuppressedExtensions() {
        return Collections.<String>singleton(JavaExtensionDef.EXTENSION_NAME);
    }

    private static final class Query1 implements GradleModelDefQuery1 {

        private static final Collection<Class<?>> RESULT = Collections.<Class<?>>singleton(GradleBuild.class);

        @Override
        public Collection<Class<?>> getToolingModels(GradleTarget gradleTarget) {
            return RESULT;
        }
    }

    private static final class Query2 implements GradleModelDefQuery1 {

        private static final Collection<Class<?>> RESULT = Collections.<Class<?>>singleton(AndroidProject.class);

        @Override
        public Collection<Class<?>> getToolingModels(GradleTarget gradleTarget) {
            return RESULT;
        }
    }


}

package com.didi.virtualapk

import com.android.build.gradle.AppExtension
import com.android.build.gradle.BaseExtension
import com.android.build.gradle.internal.api.ApplicationVariantImpl
import com.android.build.gradle.internal.plugins.AppPlugin
import com.android.build.gradle.internal.scope.GlobalScope
import com.android.build.gradle.internal.tasks.ApplicationTaskManager
import com.android.build.gradle.internal.tasks.factory.TaskFactory
import com.android.build.gradle.internal.variant.LegacyVariantInputManager
import com.android.build.gradle.internal.variant.VariantFactory
import com.didi.virtualapk.tasks.AssemblePlugin
import com.didi.virtualapk.utils.Log
import com.didi.virtualapk.utils.Reflect
import org.gradle.api.Action
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.internal.reflect.Instantiator
import org.gradle.tooling.provider.model.ToolingModelBuilderRegistry
import org.gradle.util.NameMatcher

import javax.inject.Inject
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Base class of VirtualApk plugin, we create assemblePlugin task here
 * @author zhengtao
 */
public abstract class BasePlugin implements Plugin<Project> {

    protected Project project
    protected Instantiator instantiator
    protected TaskFactory taskFactory

    boolean checkVariantFactoryInvoked

    @Inject
    public BasePlugin(Instantiator instantiator, ToolingModelBuilderRegistry registry) {
        this.instantiator = instantiator
    }

    @Override
    public void apply(Project project) {
        this.project = project
        project.ext.set(Constants.GRADLE_3_1_0, false)

        try {
            Class.forName('com.android.builder.core.VariantConfiguration')
        } catch (Throwable e) {
            // com.android.tools.build:gradle:3.1.0
            project.ext.set(Constants.GRADLE_3_1_0, true)
        }

        AppPlugin appPlugin = project.plugins.findPlugin(AppPlugin)
        Reflect reflect = Reflect.on(appPlugin.variantManager)
        VariantFactory variantFactory = Proxy.newProxyInstance(this.class.classLoader, [VariantFactory.class] as Class[],
                new InvocationHandler() {
                    Object delegate = reflect.get('variantFactory')

                    @Override
                    Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if ('preVariantWork' == method.name) {
                            checkVariantFactoryInvoked = true
                            Log.i 'VAPlugin', "Evaluating VirtualApk's configurations..."
                            boolean isBuildingPlugin = evaluateBuildingPlugin(appPlugin, project)
                            beforeCreateAndroidTasks(isBuildingPlugin)
                        }

                        return method.invoke(delegate, args)
                    }
                })
        reflect.set('variantFactory', variantFactory)

        project.extensions.create('virtualApk', VAExtention)

        if (project.extensions.extraProperties.get(Constants.GRADLE_3_1_0)) {
            LegacyVariantInputManager variantInputModel = Reflect.on(appPlugin).field("variantInputModel").get()
            GlobalScope globalScope = Reflect.on(appPlugin).field("globalScope").get()
            BaseExtension extension = Reflect.on(appPlugin).field("extension").get()
            ApplicationTaskManager taskManager = Reflect.on(appPlugin).call("createTaskManager",
                    appPlugin.variantManager.getMainComponents(),
                    appPlugin.variantManager.getTestComponents(),
                    !variantInputModel.getProductFlavors().isEmpty(),
                    globalScope,
                    extension
            ).get()
            taskFactory = taskManager.taskFactory
        } else {
            taskFactory = Reflect.on('com.android.build.gradle.internal.TaskManager')
                    .create(project.tasks)
                    .get()
        }
        project.afterEvaluate {

            if (!checkVariantFactoryInvoked) {
                throw new RuntimeException('Evaluating VirtualApk\'s configurations has failed!')
            }

            android.applicationVariants.each { ApplicationVariantImpl variant ->
                if ('release' == variant.buildType.name) {
                    String variantAssembleTaskName = variant.variantData.scope.getTaskName('assemble', 'Plugin')
                    def final variantPluginTaskName = createPluginTaskName(variantAssembleTaskName)
                    final def configAction = new AssemblePlugin.ConfigAction(project, variant)

                    taskFactory.create(variantPluginTaskName, AssemblePlugin, configAction)

                    Action action = new Action<Task>() {
                        @Override
                        void execute(Task task) {
                            task.dependsOn(variantPluginTaskName)
                        }
                    }

                    if (project.extensions.extraProperties.get(Constants.GRADLE_3_1_0)) {
                        taskFactory.configure("assemblePlugin", action)
                    } else {
                        taskFactory.named("assemblePlugin", action)
                    }
                }
            }
        }

        project.task('assemblePlugin', dependsOn: "assembleRelease", group: 'build', description: 'Build plugin apk')
    }

    String createPluginTaskName(String name) {
        if (name == 'assembleReleasePlugin') {
            return '_assemblePlugin'
        }
        return name.replace('Release', '')
    }

    private boolean evaluateBuildingPlugin(AppPlugin appPlugin, Project project) {
        def startParameter = project.gradle.startParameter
        def targetTasks = startParameter.taskNames

        def pluginTasks = ['assemblePlugin'] as List<String>

        appPlugin.variantManager.variantInputModel.buildTypes.each {
            def buildType = it.value.buildType
            if ('release' != buildType.name) {
                return
            }
            if (appPlugin.variantManager.variantInputModel.productFlavors.isEmpty()) {
                return
            }
            appPlugin.variantManager.variantInputModel.productFlavors
            appPlugin.variantManager.variantInputModel.productFlavors.each {
                String variantName = it.key + it.value.productFlavor.name

//                if (project.extensions.extraProperties.get(Constants.GRADLE_3_1_0)) {
//                    variantName = Reflect.on('com.android.build.gradle.internal.core.VariantConfiguration')
//                            .call('computeFullName', it.key, buildType, VariantType.DEFAULT, null)
//                            .get()
//
//                } else {
//
//                    variantName = Reflect.on('com.android.builder.core.VariantConfiguration')
//                            .call('computeFullName', it.key, buildType, VariantType.DEFAULT, null)
//                            .get()
//                }
                def variantPluginTaskName = createPluginTaskName("assemble${variantName.capitalize()}Plugin".toString())
                pluginTasks.add(variantPluginTaskName)
            }
        }

//        pluginTasks.each {
//            Log.i 'VAPlugin', "pluginTask: ${it}"
//        }

        boolean isBuildingPlugin = false
        NameMatcher nameMatcher = new NameMatcher()
        targetTasks.every {
            int index = it.lastIndexOf(":");
            String task = index >= 0 ? it.substring(index + 1) : it
            String taskName = nameMatcher.find(task, pluginTasks)
            if (taskName != null) {
//                Log.i 'VAPlugin', "Found task name '${taskName}' by given name '${it}'"
                isBuildingPlugin = true
                return false
            }
            return true
        }

        return isBuildingPlugin
    }

    protected abstract void beforeCreateAndroidTasks(boolean isBuildingPlugin)

    protected final VAExtention getVirtualApk() {
        return this.project.virtualApk
    }

    protected final AppExtension getAndroid() {
        return this.project.android
    }
}

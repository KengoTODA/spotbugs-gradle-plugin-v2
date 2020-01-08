/*
 * Copyright 2019 SpotBugs team
 *
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.spotbugs.snom;

import com.github.spotbugs.snom.internal.SpotBugsHtmlReport;
import com.github.spotbugs.snom.internal.SpotBugsRunnerForJavaExec;
import com.github.spotbugs.snom.internal.SpotBugsRunnerForWorker;
import com.github.spotbugs.snom.internal.SpotBugsTextReport;
import com.github.spotbugs.snom.internal.SpotBugsXmlReport;
import edu.umd.cs.findbugs.annotations.NonNull
import edu.umd.cs.findbugs.annotations.Nullable;
import edu.umd.cs.findbugs.annotations.OverrideMustInvoke;
import org.gradle.api.tasks.SkipWhenEmpty

import org.gradle.api.Action;
import org.gradle.api.DefaultTask;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.FileCollection;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.util.ClosureBackedAction;
import org.gradle.workers.WorkerExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory

/**
 * The Gradle task to run the SpotBugs analysis. All properties are optional.
 *
 * <p><strong>Usage for Java project:</strong>
 * <p>After you apply the SpotBugs Gradle plugin to project, {@code SpotBugsTask} is automatically
 * generated for each sourceSet. If you want to configure generated tasks, write build scripts like below:<div><code>
 * spotbugsMain {<br>
 * &nbsp;&nbsp;&nbsp;&nbsp;sourceDirs = sourceSets.main.allSource.srcDirs<br>
 * &nbsp;&nbsp;&nbsp;&nbsp;classDirs = sourceSets.main.output<br>
 * &nbsp;&nbsp;&nbsp;&nbsp;auxClassPaths = sourceSets.main.compileClasspath<br>
 * <br>
 * &nbsp;&nbsp;&nbsp;&nbsp;ignoreFailures = false<br>
 * &nbsp;&nbsp;&nbsp;&nbsp;showProgress = false<br>
 * &nbsp;&nbsp;&nbsp;&nbsp;reportLevel = 'default'<br>
 * &nbsp;&nbsp;&nbsp;&nbsp;effort = 'default'<br>
 * &nbsp;&nbsp;&nbsp;&nbsp;visitors = [ 'FindSqlInjection', 'SwitchFallthrough' ]<br>
 * &nbsp;&nbsp;&nbsp;&nbsp;omitVisitors = [ 'FindNonShortCircuit' ]<br>
 * &nbsp;&nbsp;&nbsp;&nbsp;reportsDir = file("$buildDir/reports/spotbugs")<br>
 * &nbsp;&nbsp;&nbsp;&nbsp;includeFilter = file('spotbugs-include.xml')<br>
 * &nbsp;&nbsp;&nbsp;&nbsp;excludeFilter = file('spotbugs-exclude.xml')<br>
 * &nbsp;&nbsp;&nbsp;&nbsp;onlyAnalyze = ['com.foobar.MyClass', 'com.foobar.mypkg.*']<br>
 * &nbsp;&nbsp;&nbsp;&nbsp;projectName = name<br>
 * &nbsp;&nbsp;&nbsp;&nbsp;release = version<br>
 * &nbsp;&nbsp;&nbsp;&nbsp;extraArgs = [ '-nested:false' ]<br>
 * &nbsp;&nbsp;&nbsp;&nbsp;jvmArgs = [ '-Duser.language=ja' ]<br>
 * &nbsp;&nbsp;&nbsp;&nbsp;maxHeapSize = '512m'<br>
 *}</code></div>
 *
 * <p>See also <a href="https://spotbugs.readthedocs.io/en/stable/running.html">SpotBugs Manual about configuration</a>.</p>
 */
abstract class SpotBugsTask extends DefaultTask {
    private static final String FEATURE_FLAG_WORKER_API = "com.github.spotbugs.snom.worker";
    private final Logger log = LoggerFactory.getLogger(SpotBugsTask);

    private final WorkerExecutor workerExecutor;

    @Input
    @Optional
    @NonNull final Property<Boolean> ignoreFailures;
    /**
     * Property to enable progress reporting during the analysis. Default value is {@code false}.
     */
    @Input
    @Optional
    @NonNull
    final Property<Boolean> showProgress;
    /**
     * Property to specify the level to report bugs. Default value is {@link Confidence#DEFAULT}.
     */
    @Input
    @Optional
    @NonNull
    final Property<Confidence> reportLevel;
    /**
     * Property to adjust SpotBugs detectors. Default value is {@link Effort#DEFAULT}.
     */
    @Input
    @Optional
    @NonNull
    final Property<Effort> effort;
    /**
     * Property to enable visitors (detectors) for analysis. Default is empty that means all visitors run analysis.
     */
    @Input
    @NonNull
    final ListProperty<String> visitors;
    /**
     * Property to disable visitors (detectors) for analysis. Default is empty that means SpotBugs omits no visitor.
     */
    @Input
    @NonNull
    final ListProperty<String> omitVisitors;

    /**
     * Property to set the directory to generate report files. Default is {@code "$buildDir/reports/spotbugs/$taskName"}.
     */
    @Internal("Refer the destination of each report instead.")
    @NonNull
    final Property<File> reportsDir;

    /**
     * Property to specify which report you need.
     *
     * @see SpotBugsReport
     */
    @Nested
    @NonNull
    final NamedDomainObjectContainer<SpotBugsReport> reports;

    /**
     * Property to set the filter file to limit which bug should be reported.
     *
     * <p>Note that this property will NOT limit which bug should be detected. To limit the target classes to analyze, use {@link #onlyAnalyze} instead.
     * To limit the visitors (detectors) to run, use {@link #visitors} and {@link #omitVisitors} instead.</p>
     *
     * <p>See also <a href="https://spotbugs.readthedocs.io/en/stable/filter.html>SpotBugs Manual about Filter file</a>.</p>
     */
    @Optional
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    @NonNull
    final Property<File> includeFilter;
    /**
     * Property to set the filter file to limit which bug should be reported.
     *
     * <p>Note that this property will NOT limit which bug should be detected. To limit the target classes to analyze, use {@link #onlyAnalyze} instead.
     * To limit the visitors (detectors) to run, use {@link #visitors} and {@link #omitVisitors} instead.</p>
     *
     * <p>See also <a href="https://spotbugs.readthedocs.io/en/stable/filter.html>SpotBugs Manual about Filter file</a>.</p>
     */
    @Optional
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    @NonNull
    final Property<File> excludeFilter;
    /**
     * Property to specify the target classes for analysis. Default value is empty that means all classes are analyzed.
     */
    @Input
    @NonNull
    final ListProperty<String> onlyAnalyze;
    /**
     * Property to specify the name of project. Some reporting formats use this property.
     * Default value is {@code "${project.name} (${task.name})"}.
     */
    @Input
    @NonNull
    final Property<String> projectName;
    /**
     * Property to specify the release identifier of project. Some reporting formats use this property. Default value is the version of your Gradle project.
     */
    @Input
    @NonNull
    final Property<String> release;
    /**
     * Property to specify the extra arguments for SpotBugs. Default value is empty so SpotBugs will get no extra argument.
     */
    @Optional
    @Input
    @NonNull
    final ListProperty<String> extraArgs;
    /**
     * Property to specify the extra arguments for JVM process. Default value is empty so JVM process will get no extra argument.
     */
    @Optional
    @Input
    @NonNull
    final ListProperty<String> jvmArgs;
    /**
     * Property to specify the max heap size ({@code -Xmx} option) of JVM process.
     * Default value is empty so the default configuration made by Gradle will be used.
     */
    @Optional
    @Input
    @NonNull
    final Property<String> maxHeapSize;
    /**
     * Property to specify the directories that contain the source of target classes to analyze.
     * Default value is the source directory of the target sourceSet.
     */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    @NonNull
    abstract FileCollection getSourceDirs();
    /**
     * Property to specify the directories that contains the target classes to analyze.
     * Default value is the output directory of the target sourceSet.
     */
    @Internal
    @NonNull
    abstract FileCollection getClassDirs();
    /**
     * Property to specify the aux class paths that contains the libraries to refer during analysis.
     * Default value is the compile-scope dependencies of the target sourceSet.
     */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    @NonNull
    abstract FileCollection getAuxClassPaths();

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    @SkipWhenEmpty
    @NonNull
    FileCollection getTargetClassFiles() {
        getClassDirs().asFileTree
    }

    SpotBugsTask(ObjectFactory objects, WorkerExecutor workerExecutor) {
        this.workerExecutor = Objects.requireNonNull(workerExecutor);

        ignoreFailures = objects.property(Boolean);
        showProgress = objects.property(Boolean);
        reportLevel = objects.property(Confidence);
        effort = objects.property(Effort);
        visitors = objects.listProperty(String);
        omitVisitors = objects.listProperty(String);
        reportsDir = objects.property(File);
        reports =
                objects.domainObjectContainer(
                SpotBugsReport, {name ->
                    switch (name) {
                        case "html":
                            return new SpotBugsHtmlReport(objects, this);
                        case "xml":
                            return new SpotBugsXmlReport(objects, this);
                        case "text":
                            return new SpotBugsTextReport(objects, this);
                        default:
                            throw new InvalidUserDataException(name + " is invalid as the report name");
                    }
                });
        includeFilter = objects.property(File);
        excludeFilter = objects.property(File);
        onlyAnalyze = objects.listProperty(String);
        projectName = objects.property(String);
        release = objects.property(String);
        jvmArgs = objects.listProperty(String);
        extraArgs = objects.listProperty(String);
        maxHeapSize = objects.property(String);
    }

    /**
     * Set properties from extension right after the task creation. User may overwrite these
     * properties by build script.
     *
     * @param extension the source extension to copy the properties.
     */
    @OverrideMustInvoke
    protected void init(SpotBugsExtension extension) {
        ignoreFailures.set(extension.ignoreFailures)
        showProgress.set(extension.showProgress)
        reportLevel.set(extension.reportLevel)
        effort.set(extension.effort)
        visitors.set(extension.visitors)
        omitVisitors.set(extension.omitVisitors)
        // the default reportsDir is "$buildDir/reports/spotbugs/${taskName}"
        reportsDir.set(extension.reportsDir.map({dir -> new File(dir, getName())}))
        includeFilter.set(extension.includeFilter)
        excludeFilter.set(extension.excludeFilter)
        onlyAnalyze.set(extension.onlyAnalyze)
        projectName.set(extension.projectName.map({p -> String.format("%s (%s)", p, getName())}))
        release.set(extension.release)
        jvmArgs.set(extension.jvmArgs)
        extraArgs.set(extension.extraArgs)
        maxHeapSize.set(extension.maxHeapSize)
    }

    @TaskAction
    void run() {
        if (getProject().hasProperty(FEATURE_FLAG_WORKER_API)
        && getProject()
        .property(FEATURE_FLAG_WORKER_API)
        .toString() == "true") {
            log.info("Experimental: Try to run SpotBugs in the worker process.");
            new SpotBugsRunnerForWorker(workerExecutor).run(this);
        } else {
            new SpotBugsRunnerForJavaExec().run(this);
        }
    }

    final NamedDomainObjectContainer<? extends SpotBugsReport> reports(
            Closure<NamedDomainObjectContainer<? extends SpotBugsReport>> closure) {
        return reports(
                new ClosureBackedAction<NamedDomainObjectContainer<? extends SpotBugsReport>>(closure))
    }

    final NamedDomainObjectContainer<? extends SpotBugsReport> reports(
            Action<NamedDomainObjectContainer<? extends SpotBugsReport>> configureAction) {
        configureAction.execute(reports)
        return reports
    }

    @NonNull
    @Internal
    Set<File> getPluginJar() {
        return getProject().getConfigurations().getByName("spotbugsPlugin").getFiles()
    }

    @NonNull
    @Internal
    Set<File> getJarOnClasspath() {
        Configuration config = getProject().getConfigurations().getByName(SpotBugsPlugin.CONFIG_NAME)
        Configuration spotbugsSlf4j = getProject().getConfigurations().getByName("spotbugsSlf4j")

        Set<File> spotbugsJar = config.getFiles()
        log.info("SpotBugs jar file: {}", spotbugsJar)
        Set<File> slf4jJar = spotbugsSlf4j.getFiles()
        log.info("SLF4J provider jar file: {}", slf4jJar)

        Set<File> jarOnClasspath = new HashSet<>()
        jarOnClasspath.addAll(spotbugsJar)
        jarOnClasspath.addAll(slf4jJar)
        return jarOnClasspath
    }

    @NonNull
    @Nested
    java.util.Optional<SpotBugsReport> getFirstEnabledReport() {
        return reports.stream().filter({report -> report.enabled}).findFirst()
    }

    void setReportLevel(@Nullable String name) {
        Confidence confidence = name == null ? null : Confidence.valueOf(name.toUpperCase())
        getReportLevel().set(confidence)
    }

    void setEffort(@Nullable String name) {
        Effort effort = name == null ? null : Effort.valueOf(name.toUpperCase())
        getEffort().set(effort)
    }
}

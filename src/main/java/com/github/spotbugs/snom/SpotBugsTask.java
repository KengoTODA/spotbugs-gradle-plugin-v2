/**
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
import com.github.spotbugs.snom.internal.SpotBugsTextReport;
import com.github.spotbugs.snom.internal.SpotBugsXmlReport;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.OverrideMustInvoke;
import groovy.lang.Closure;
import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import org.gradle.api.Action;
import org.gradle.api.DefaultTask;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.FileCollection;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;
import org.gradle.api.reporting.SingleFileReport;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class SpotBugsTask extends DefaultTask
// TODO consider to implements VerificationTask
{
  private final Logger log = LoggerFactory.getLogger(SpotBugsTask.class);
  @NonNull final Property<Boolean> ignoreFailures;
  @NonNull final Property<Boolean> showProgress;
  @NonNull final Property<Confidence> reportLevel;
  @NonNull final Property<Effort> effort;
  @NonNull final ListProperty<String> visitors;
  @NonNull final ListProperty<String> omitVisitors;
  @NonNull final Property<File> reportsDir;
  @NonNull final NamedDomainObjectContainer<SpotBugsReport> reports;

  @NonNull final Property<File> includeFilter;
  @NonNull final Property<File> excludeFilter;
  @NonNull final ListProperty<String> onlyAnalyze;

  @InputFiles
  @PathSensitive(PathSensitivity.RELATIVE)
  @NonNull
  public abstract FileCollection getSourceDirs();

  @InputFiles
  @PathSensitive(PathSensitivity.RELATIVE)
  @NonNull
  public abstract FileCollection getClassDirs();

  @InputFiles
  @PathSensitive(PathSensitivity.RELATIVE)
  @NonNull
  public abstract FileCollection getAuxClassPaths();

  @Input
  @Optional
  @NonNull
  public Property<Boolean> getIgnoreFailures() {
    return ignoreFailures;
  }

  @Input
  @Optional
  @NonNull
  public Property<Boolean> getShowProgress() {
    return showProgress;
  }

  @Input
  @Optional
  @NonNull
  public Property<Confidence> getReportLevel() {
    return reportLevel;
  }

  @Input
  @Optional
  @NonNull
  public Property<Effort> getEffort() {
    return effort;
  }

  @Input
  @NonNull
  public ListProperty<String> getVisitors() {
    return visitors;
  }

  @Input
  @NonNull
  public ListProperty<String> getOmitVisitors() {
    return omitVisitors;
  }

  @NonNull
  @Internal("Refer the destination of each report instead.")
  public Property<File> getReportsDir() {
    return reportsDir;
  }

  public void setReportsDir(Provider<File> provider) {
    reportsDir.set(provider);
  }

  @NonNull
  @Optional
  @InputFile
  @PathSensitive(PathSensitivity.RELATIVE)
  public Property<File> getIncludeFilter() {
    return includeFilter;
  }

  @NonNull
  @Optional
  @InputFile
  @PathSensitive(PathSensitivity.RELATIVE)
  public Property<File> getExcludeFilter() {
    return excludeFilter;
  }

  @NonNull
  @Input
  public ListProperty<String> getOnlyAnalyze() {
    return onlyAnalyze;
  }

  public SpotBugsTask(ObjectFactory objects) {
    ignoreFailures = objects.property(Boolean.class);
    showProgress = objects.property(Boolean.class);
    reportLevel = objects.property(Confidence.class);
    effort = objects.property(Effort.class);
    visitors = objects.listProperty(String.class);
    omitVisitors = objects.listProperty(String.class);
    reportsDir = objects.property(File.class);
    reports =
        objects.domainObjectContainer(
            SpotBugsReport.class,
            (name) -> {
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
    includeFilter = objects.property(File.class);
    excludeFilter = objects.property(File.class);
    onlyAnalyze = objects.listProperty(String.class);
  }

  /**
   * Set properties from extension right after the task creation. User may overwrite these
   * properties by build script.
   *
   * @param extension the source extension to copy the properties.
   */
  @OverrideMustInvoke
  protected void init(SpotBugsExtension extension) {
    ignoreFailures.set(extension.ignoreFailures);
    showProgress.set(extension.showProgress);
    reportLevel.set(extension.reportLevel);
    effort.set(extension.effort);
    visitors.set(extension.visitors);
    omitVisitors.set(extension.omitVisitors);
    // the default reportsDir is "$buildDir/reports/spotbugs/${taskName}"
    reportsDir.set(extension.reportsDir.map(dir -> new File(dir, getName())));
    includeFilter.set(extension.includeFilter);
    excludeFilter.set(extension.excludeFilter);
    onlyAnalyze.set(extension.onlyAnalyze);
  }

  final void applyTo(ImmutableSpotBugsSpec.Builder builder) {
    builder.isIgnoreFailures(ignoreFailures.getOrElse(false));
    builder.isShowProgress(showProgress.getOrElse(false));
    getFirstEnabledReport()
        .ifPresent(
            report -> {
              File dir = report.getDestination().getParentFile();
              dir.mkdirs();
              report.toCommandLineOption().ifPresent(builder::addExtraArguments);
              builder.addExtraArguments("-outputFile", report.getDestination().getAbsolutePath());
            });
    if (effort.isPresent()) {
      builder.addExtraArguments("-effort:" + effort.get().toString().toLowerCase());
    }
    if (reportLevel.isPresent()) {
      builder.addExtraArguments(reportLevel.get().toCommandLineOption());
    }
    if (visitors.isPresent() && !visitors.get().isEmpty()) {
      builder.addExtraArguments("-visitors");
      builder.addExtraArguments(visitors.get().stream().collect(Collectors.joining(",")));
    }
    if (omitVisitors.isPresent() && !omitVisitors.get().isEmpty()) {
      builder.addExtraArguments("-omitVisitors");
      builder.addExtraArguments(omitVisitors.get().stream().collect(Collectors.joining(",")));
    }
    if (includeFilter.isPresent() && includeFilter.get() != null) {
      builder.addExtraArguments("-include", includeFilter.get().getAbsolutePath());
    }
    if (excludeFilter.isPresent() && excludeFilter.get() != null) {
      builder.addExtraArguments("-exclude", excludeFilter.get().getAbsolutePath());
    }
    if (onlyAnalyze.isPresent() && !onlyAnalyze.get().isEmpty()) {
      builder.addExtraArguments(
          "-onlyAnalyze", onlyAnalyze.get().stream().collect(Collectors.joining(",")));
    }
    builder
        .sourceDirs(getSourceDirs())
        .addAllClassDirs(getClassDirs())
        .addAllAuxClassPaths(getAuxClassPaths());
  }

  @TaskAction
  public void run() {
    ImmutableSpotBugsSpec.Builder builder = ImmutableSpotBugsSpec.builder();
    applyTo(builder);
    Configuration pluginConfig = getProject().getConfigurations().getByName("spotbugsPlugin");
    builder.addAllPlugins(pluginConfig.getFiles());
    getProject()
        .javaexec(
            spec -> {
              spec.setIgnoreExitValue(ignoreFailures.getOrElse(false));
              spec.classpath(createJarOnClasspath());
              spec.setMain("edu.umd.cs.findbugs.FindBugs2");
              builder.build().applyTo(spec);
            });
  }

  @Nested
  public final NamedDomainObjectContainer<? extends SingleFileReport> getReports() {
    return reports;
  }

  public final NamedDomainObjectContainer<? extends SpotBugsReport> reports(
      Closure<NamedDomainObjectContainer<? extends SpotBugsReport>> closure) {
    return reports(
        new ClosureBackedAction<NamedDomainObjectContainer<? extends SpotBugsReport>>(closure));
  }

  public final NamedDomainObjectContainer<? extends SpotBugsReport> reports(
      Action<NamedDomainObjectContainer<? extends SpotBugsReport>> configureAction) {
    configureAction.execute(reports);
    return reports;
  }

  @NonNull
  private Set<File> createJarOnClasspath() {
    Configuration config = getProject().getConfigurations().getByName(SpotBugsPlugin.CONFIG_NAME);
    Configuration spotbugsSlf4j = getProject().getConfigurations().getByName("spotbugsSlf4j");

    Set<File> spotbugsJar = config.getFiles();
    log.info("SpotBugs jar file: {}", spotbugsJar);
    Set<File> slf4jJar = spotbugsSlf4j.getFiles();
    log.info("SLF4J provider jar file: {}", slf4jJar);

    Set<File> jarOnClasspath = new HashSet<>();
    jarOnClasspath.addAll(spotbugsJar);
    jarOnClasspath.addAll(slf4jJar);
    return jarOnClasspath;
  }

  @NonNull
  @Nested
  public java.util.Optional<SpotBugsReport> getFirstEnabledReport() {
    return reports.stream().filter(SingleFileReport::isEnabled).findFirst();
  }
}

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
package com.github.spotbugs.snom.internal;

import com.github.spotbugs.snom.SpotBugsReport;
import com.github.spotbugs.snom.SpotBugsTask;
import edu.umd.cs.findbugs.annotations.NonNull;
import edu.umd.cs.findbugs.annotations.Nullable;
import java.io.File;
import java.util.Optional;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.logging.Logger;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.resources.ResourceHandler;
import org.gradle.api.resources.TextResource;
import org.gradle.api.resources.TextResourceFactory;

public class SpotBugsHtmlReport extends SpotBugsReport {

  private final Property<TextResource> stylesheet;
  private final Property<String> stylesheetPath;
  private final transient ResourceHandler handler;
  private final transient Logger logger;

  public SpotBugsHtmlReport(ObjectFactory objects, SpotBugsTask task) {
    super(objects, task);
    handler = task.getProject().getResources();
    stylesheetPath = objects.property(String.class);
    stylesheet = objects.property(TextResource.class);
    logger = task.getLogger();
    // the default reportsDir is "$buildDir/reports/spotbugs/${taskName}/spotbugs.html"
    setDestination(task.getReportsDir().map(dir -> new File(dir, "spotbugs.html")));
  }

  @NonNull
  @Override
  public Optional<String> toCommandLineOption() {
    @Nullable TextResource stylesheet = getStylesheet();

    if (stylesheet == null) {
      return Optional.of("-html");
    } else {
      return Optional.of("-html:" + stylesheet.asFile().getAbsolutePath());
    }
  }

  @Override
  public String getName() {
    return "HTML";
  }

  @Override
  public TextResource getStylesheet() {
    if (!stylesheet.isPresent() && !stylesheetPath.isPresent()) {
      return super.getStylesheet();
    }

    if (stylesheet.isPresent()) {
      return stylesheet.get();
    }

    TextResourceFactory factory = handler.getText();
    Optional<File> spotbugs =
        getTask().getProject().getConfigurations().getByName("spotbugs")
            .files(
                dependency ->
                    dependency.getGroup().equals("com.github.spotbugs")
                        && dependency.getName().equals("spotbugs"))
            .stream()
            .findFirst();
    if (spotbugs.isPresent()) {
      File jar = spotbugs.get();
      logger.debug(
          "Specified stylesheet ({}) found in spotbugs configuration: {}",
          stylesheetPath.get(),
          jar.getAbsolutePath());
      return factory.fromArchiveEntry(jar, stylesheetPath.get());
    } else {
      throw new InvalidUserDataException(
          "Specified stylesheet ("
              + stylesheetPath.get()
              + ") does not found in spotbugs configuration");
    }
  }

  @Override
  public void setStylesheet(@Nullable String path) {
    stylesheetPath.set(path);
  }

  @Override
  public void setStylesheet(@Nullable TextResource textResource) {
    stylesheet.set(textResource);
  }
}

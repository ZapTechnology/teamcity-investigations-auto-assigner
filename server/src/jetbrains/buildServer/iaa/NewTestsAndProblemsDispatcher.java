/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package jetbrains.buildServer.iaa;

import jetbrains.buildServer.BuildProblemData;
import jetbrains.buildServer.serverSide.*;
import jetbrains.buildServer.serverSide.impl.problems.BuildProblemImpl;
import jetbrains.buildServer.serverSide.problems.BuildProblem;
import jetbrains.buildServer.serverSide.stat.BuildTestsListener;
import jetbrains.buildServer.tests.TestName;
import jetbrains.buildServer.util.executors.ExecutorsFactory;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * @author Maxim.Manuylov
 *         Date: 09.04.2014
 */
public class NewTestsAndProblemsDispatcher {
  @NotNull private final NewTestsAndProblemsProcessor myProcessor;
  @NotNull private final BuildsManager myBuildsManager;
  @NotNull private final ExecutorService myQueue;

  public NewTestsAndProblemsDispatcher(@NotNull final BuildTestsEventDispatcher buildTestsEventDispatcher,
                                       @NotNull final BuildServerListenerEventDispatcher buildServerListenerEventDispatcher,
                                       @NotNull final NewTestsAndProblemsProcessor processor,
                                       @NotNull final BuildsManager buildsManager) {
    myProcessor = processor;
    myBuildsManager = buildsManager;
    myQueue = ExecutorsFactory.newExecutor("Investigator-Auto-Assigner-");

    buildTestsEventDispatcher.addListener(new BuildTestsListener() {
      public void testPassed(@NotNull SRunningBuild sRunningBuild, @NotNull List<Long> list) {

      }

      public void testFailed(@NotNull SRunningBuild sRunningBuild, @NotNull List<Long> list) {
        BuildStatistics fullStatistics = sRunningBuild.getFullStatistics();
        final List<STestRun> testRuns = fullStatistics.getFailedTests();
       for (STestRun testRun : testRuns) {
          onTestFailed(sRunningBuild, testRun);
        }
      }

      public void testIgnored(@NotNull SRunningBuild sRunningBuild, @NotNull List<Long> list) {

      }
    });

    buildServerListenerEventDispatcher.addListener(new BuildServerAdapter() {
      @Override
      public void buildProblemsChanged(@NotNull SBuild build, @NotNull List<BuildProblemData> before, @NotNull List<BuildProblemData> after) {
        if (!(build instanceof BuildEx)) return;
        final List<BuildProblemData> newProblems = new ArrayList<BuildProblemData>(after);
        newProblems.removeAll(before);
        for (BuildProblemData newProblem : newProblems) {
          onBuildProblemOccurred((BuildEx) build, newProblem);
        }
      }

      @Override
      public void serverShutdown() {
        myQueue.shutdown();
      }
    });
  }

  private void onTestFailed(@NotNull final SRunningBuild build, @NotNull final STestRun testRun) {
    myQueue.submit(new Runnable() {
      public void run() {
        myProcessor.onTestFailed(build, testRun);
      }
    });
  }

  private void onBuildProblemOccurred(@NotNull final BuildEx build, @NotNull final BuildProblemData problem) {
    myQueue.submit(new Runnable() {
      public void run() {
        final List<BuildProblem> buildProblems = build.getBuildProblems();
        BuildProblemImpl.fillIsNew(buildProblems, myBuildsManager, build); // workaround
        for (BuildProblem buildProblem : buildProblems) {
          if (buildProblem.getBuildProblemData().equals(problem)) {
            if (buildProblem instanceof BuildProblemImpl) {
              myProcessor.onBuildProblemOccurred(build, (BuildProblemImpl) buildProblem);
            }
            break;
          }
        }
      }
    });
  }
}

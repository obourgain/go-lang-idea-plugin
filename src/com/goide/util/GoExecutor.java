package com.goide.util;

import com.goide.GoConstants;
import com.goide.GoEnvironmentUtil;
import com.goide.runconfig.GoConsoleFilter;
import com.goide.sdk.GoSdkService;
import com.goide.sdk.GoSdkUtil;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.ExecutionHelper;
import com.intellij.execution.ExecutionModes;
import com.intellij.execution.RunContentExecutor;
import com.intellij.execution.configurations.EncodingEnvironmentUtil;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.ParametersList;
import com.intellij.execution.process.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.CharsetToolkit;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.util.ObjectUtils;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

public class GoExecutor {
  private static final ExecutionModes.SameThreadMode EXECUTION_MODE = new ExecutionModes.SameThreadMode(true);

  @NotNull private final Map<String, String> myExtraEnvironment = ContainerUtil.newHashMap();
  @NotNull private final ParametersList myParameterList = new ParametersList();
  @NotNull private ProcessOutput myProcessOutput = new ProcessOutput();
  @NotNull private Project myProject;
  @Nullable private final Module myModule;
  @Nullable private String myGoRoot;
  @Nullable private String myGoPath;
  @Nullable private String myWorkDirectory;
  private boolean myShowOutputOnError = false;
  private boolean myPassParentEnvironment = true;
  @Nullable private String myExePath = null;
  @Nullable private String myPresentableName;

  private GoExecutor(@NotNull Project project, @Nullable Module module) {
    myProject = project;
    myModule = module;
  }

  @NotNull
  public static GoExecutor in(@NotNull Project project) {
    return new GoExecutor(project, null)
      .withGoRoot(GoSdkService.getInstance(project).getSdkHomePath(null))
      .withGoPath(GoSdkUtil.retrieveGoPath(project));
  }

  @NotNull
  public static GoExecutor in(@NotNull Module module) {
    Project project = module.getProject();
    return new GoExecutor(project, module)
      .withGoRoot(GoSdkService.getInstance(project).getSdkHomePath(module))
      .withGoPath(GoSdkUtil.retrieveGoPath(module));
  }

  @NotNull
  public GoExecutor withPresentableName(@Nullable String presentableName) {
    myPresentableName = presentableName;
    return this;
  }

  @NotNull
  public GoExecutor withExePath(@Nullable String exePath) {
    myExePath = exePath;
    return this;
  }

  @NotNull
  public GoExecutor withWorkDirectory(@Nullable String workDirectory) {
    myWorkDirectory = workDirectory;
    return this;
  }

  @NotNull
  public GoExecutor withGoRoot(@Nullable String goRoot) {
    myGoRoot = goRoot;
    return this;
  }

  @NotNull
  public GoExecutor withGoPath(@Nullable String goPath) {
    myGoPath = goPath;
    return this;
  }

  @NotNull
  public GoExecutor withProcessOutput(@NotNull ProcessOutput processOutput) {
    myProcessOutput = processOutput;
    return this;
  }

  @NotNull
  public GoExecutor withExtraEnvironment(@NotNull Map<String, String> environment) {
    myExtraEnvironment.putAll(environment);
    return this;
  }

  @NotNull
  public GoExecutor withPassParentEnvironment(boolean passParentEnvironment) {
    myPassParentEnvironment = passParentEnvironment;
    return this;
  }

  @NotNull
  public GoExecutor addParameterString(@NotNull String parameterString) {
    myParameterList.addParametersString(parameterString);
    return this;
  }

  @NotNull
  public GoExecutor addParameters(@NotNull String... parameters) {
    myParameterList.addAll(parameters);
    return this;
  }

  @NotNull
  public GoExecutor showOutputOnError() {
    myShowOutputOnError = true;
    return this;
  }

  public boolean execute() throws ExecutionException {
    Logger.getInstance(getClass()).assertTrue(!ApplicationManager.getApplication().isDispatchThread(),
                                              "It's bad idea to run external tool on EDT");
    GeneralCommandLine commandLine = createCommandLine();

    final Ref<Boolean> result = Ref.create(false);
    final OSProcessHandler processHandler = new KillableColoredProcessHandler(commandLine);
    final HistoryProcessListener historyProcessListener = new HistoryProcessListener();
    processHandler.addProcessListener(historyProcessListener);

    final CapturingProcessAdapter processAdapter = new CapturingProcessAdapter(myProcessOutput) {
      @Override
      public void processTerminated(@NotNull final ProcessEvent event) {
        super.processTerminated(event);
        result.set(event.getExitCode() == 0);
        ApplicationManager.getApplication().invokeLater(new Runnable() {
          @Override
          public void run() {
            if (event.getExitCode() != 0) {
              showOutput(processHandler, historyProcessListener);
            }
            ApplicationManager.getApplication().runWriteAction(new Runnable() {
              @Override
              public void run() {
                VirtualFileManager.getInstance().syncRefresh();
              }
            });
          }
        });
      }
    };

    processHandler.addProcessListener(processAdapter);
    processHandler.startNotify();
    ExecutionHelper.executeExternalProcess(myProject, processHandler, EXECUTION_MODE, commandLine);

    return result.get();
  }

  private void showOutput(@NotNull OSProcessHandler originalHandler, @NotNull HistoryProcessListener historyProcessListener) {
    if (myShowOutputOnError) {
      BaseOSProcessHandler outputHandler = new ColoredProcessHandler(originalHandler.getProcess(), null);
      RunContentExecutor runContentExecutor = new RunContentExecutor(myProject, outputHandler)
        .withTitle(ObjectUtils.notNull(myPresentableName, "go"))
        .withActivateToolWindow(myShowOutputOnError)
        .withFilter(new GoConsoleFilter(myProject, myModule, StringUtil.notNullize(myWorkDirectory)));
      Disposer.register(myProject, runContentExecutor);
      runContentExecutor.run();
      historyProcessListener.apply(outputHandler);
    }
  }

  @NotNull
  public GeneralCommandLine createCommandLine() throws ExecutionException {
    if (myGoRoot == null) {
      throw new ExecutionException("Sdk is not set or Sdk home path is empty for module");
    }

    String executable = GoEnvironmentUtil.getExecutableForSdk(myGoRoot).getAbsolutePath();
    GeneralCommandLine commandLine = new GeneralCommandLine();
    commandLine.setExePath(ObjectUtils.notNull(myExePath, executable));
    commandLine.getEnvironment().putAll(myExtraEnvironment);
    commandLine.getEnvironment().put(GoConstants.GO_ROOT, StringUtil.notNullize(myGoRoot));
    commandLine.getEnvironment().put(GoConstants.GO_PATH, StringUtil.notNullize(myGoPath));
    commandLine.withWorkDirectory(myWorkDirectory);
    commandLine.addParameters(myParameterList.getList());
    commandLine.setPassParentEnvironment(myPassParentEnvironment);
    commandLine.withCharset(CharsetToolkit.UTF8_CHARSET);
    EncodingEnvironmentUtil.setLocaleEnvironmentIfMac(commandLine);
    return commandLine;
  }


  private static class HistoryProcessListener extends ProcessAdapter {
    private ConcurrentLinkedQueue<Pair<ProcessEvent, Key>> myHistory = new ConcurrentLinkedQueue<Pair<ProcessEvent, Key>>();

    @Override
    public void onTextAvailable(ProcessEvent event, Key outputType) {
      myHistory.add(Pair.create(event, outputType));
    }

    public void apply(ProcessHandler listener) {
      for (Pair<ProcessEvent, Key> pair : myHistory) {
        listener.notifyTextAvailable(pair.getFirst().getText(), pair.getSecond());
      }
    }
  }
}
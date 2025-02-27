/*
 * Copyright 2017 The Chromium Authors. All rights reserved.
 * Use of this source code is governed by a BSD-style license that can be
 * found in the LICENSE file.
 */
package io.flutter.view;

import com.google.common.annotations.VisibleForTesting;
import com.intellij.execution.runners.ExecutionUtil;
import com.intellij.execution.ui.layout.impl.JBRunnerTabs;
import com.intellij.icons.AllIcons;
import com.intellij.ide.browsers.BrowserLauncher;
import com.intellij.ide.ui.UISettingsListener;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.colors.EditorColorsListener;
import com.intellij.openapi.editor.colors.EditorColorsManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.SimpleToolWindowPanel;
import com.intellij.openapi.ui.VerticalFlowLayout;
import com.intellij.openapi.util.ActionCallback;
import com.intellij.openapi.util.ActiveRunnable;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.ex.ToolWindowManagerEx;
import com.intellij.serviceContainer.NonInjectable;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.IdeBorderFactory;
import com.intellij.ui.SideBorder;
import com.intellij.ui.SimpleTextAttributes;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.labels.LinkLabel;
import com.intellij.ui.components.labels.LinkListener;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentManager;
import com.intellij.ui.content.ContentManagerAdapter;
import com.intellij.ui.content.ContentManagerEvent;
import com.intellij.ui.tabs.TabInfo;
import com.intellij.util.messages.MessageBusConnection;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import com.intellij.xdebugger.XSourcePosition;
import icons.FlutterIcons;
import io.flutter.FlutterBundle;
import io.flutter.FlutterInitializer;
import io.flutter.FlutterUtils;
import io.flutter.bazel.WorkspaceCache;
import io.flutter.devtools.DevToolsUrl;
import io.flutter.inspector.DiagnosticsNode;
import io.flutter.inspector.InspectorGroupManagerService;
import io.flutter.inspector.InspectorService;
import io.flutter.inspector.InspectorSourceLocation;
import io.flutter.jxbrowser.*;
import io.flutter.run.FlutterDevice;
import io.flutter.run.daemon.DevToolsInstance;
import io.flutter.run.daemon.DevToolsService;
import io.flutter.run.daemon.FlutterApp;
import io.flutter.sdk.FlutterSdk;
import io.flutter.sdk.FlutterSdkVersion;
import io.flutter.settings.FlutterSettings;
import io.flutter.toolwindow.FlutterViewToolWindowManagerListener;
import io.flutter.utils.AsyncUtils;
import io.flutter.utils.EventStream;
import io.flutter.utils.JxBrowserUtils;
import io.flutter.vmService.ServiceExtensions;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.util.List;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

@com.intellij.openapi.components.State(
  name = "FlutterView",
  storages = {@Storage("$WORKSPACE_FILE$")}
)
public class FlutterView implements PersistentStateComponent<FlutterViewState>, Disposable {
  private static final Logger LOG = Logger.getInstance(FlutterView.class);

  private static class PerAppState extends AppState {
    ArrayList<InspectorPanel> inspectorPanels = new ArrayList<>();
    boolean sendRestartNotificationOnNextFrame = false;

    public void dispose() {
      for (InspectorPanel panel : inspectorPanels) {
        Disposer.dispose(panel);
      }
    }
  }

  public static final String TOOL_WINDOW_ID = "Flutter Inspector";

  public static final String WIDGET_TAB_LABEL = "Widgets";
  public static final String RENDER_TAB_LABEL = "Render Tree";
  public static final String PERFORMANCE_TAB_LABEL = "Performance";
  protected static final String INSTALLATION_IN_PROGRESS_LABEL = "Installing JxBrowser and DevTools...";
  protected static final String INSTALLATION_TIMED_OUT_LABEL =
    "Waiting for JxBrowser installation timed out. Restart your IDE to try again.";
  protected static final String INSTALLATION_WAIT_FAILED = "The JxBrowser installation failed unexpectedly. Restart your IDE to try again.";
  protected static final String DEVTOOLS_FAILED_LABEL = "Setting up DevTools failed.";
  protected static final int INSTALLATION_WAIT_LIMIT_SECONDS = 2000;

  protected final EventStream<Boolean> shouldAutoHorizontalScroll = new EventStream<>(FlutterViewState.AUTO_SCROLL_DEFAULT);
  protected final EventStream<Boolean> highlightNodesShownInBothTrees =
    new EventStream<>(FlutterViewState.HIGHLIGHT_NODES_SHOWN_IN_BOTH_TREES_DEFAULT);

  @NotNull
  private final FlutterViewState state = new FlutterViewState();

  @NotNull
  private final Project myProject;

  private final Map<FlutterApp, PerAppState> perAppViewState = new HashMap<>();
  private final MessageBusConnection busConnection;
  private boolean busSubscribed = false;

  private Content emptyContent;

  private FlutterViewToolWindowManagerListener toolWindowListener;
  private int devToolsInstallCount = 0;
  private final JxBrowserUtils jxBrowserUtils;
  private final JxBrowserManager jxBrowserManager;

  public FlutterView(@NotNull Project project) {
    this(project, JxBrowserManager.getInstance(), new JxBrowserUtils(), InspectorGroupManagerService.getInstance(project), ApplicationManager.getApplication().getMessageBus().connect());
  }

  @VisibleForTesting
  @NonInjectable
  protected FlutterView(@NotNull Project project, @NotNull JxBrowserManager jxBrowserManager, JxBrowserUtils jxBrowserUtils, InspectorGroupManagerService inspectorGroupManagerService, MessageBusConnection messageBusConnection) {
    myProject = project;
    this.jxBrowserUtils = jxBrowserUtils;
    this.jxBrowserManager = jxBrowserManager;
    this.busConnection = messageBusConnection;

    shouldAutoHorizontalScroll.listen(state::setShouldAutoScroll);
    highlightNodesShownInBothTrees.listen(state::setHighlightNodesShownInBothTrees);

    inspectorGroupManagerService.addListener(new InspectorGroupManagerService.Listener() {
      @Override
      public void onInspectorAvailable(InspectorService service) { }

      @Override
      public void onSelectionChanged(DiagnosticsNode selection) {
        if (selection != null) {
          final InspectorSourceLocation location = selection.getCreationLocation();
          if (location != null) {
            final XSourcePosition sourcePosition = location.getXSourcePosition();
            if (sourcePosition != null) {
              sourcePosition.createNavigatable(project).navigate(true);
            }
          }
          if (selection.isCreatedByLocalProject()) {
            final XSourcePosition position = selection.getCreationLocation().getXSourcePosition();
            if (position != null) {
              position.createNavigatable(project).navigate(false);
            }
          }
        }
      }
    }, this);
  }

  @Override
  public void dispose() {
    busConnection.disconnect();
    Disposer.dispose(this);
  }

  @NotNull
  @Override
  public FlutterViewState getState() {
    return state;
  }

  @NotNull
  public Project getProject() {
    return myProject;
  }

  @Override
  public void loadState(@NotNull FlutterViewState state) {
    this.state.copyFrom(state);

    shouldAutoHorizontalScroll.setValue(this.state.getShouldAutoScroll());
    highlightNodesShownInBothTrees.setValue(this.state.getHighlightNodesShownInBothTrees());
  }

  void initToolWindow(ToolWindow window) {
    if (window.isDisposed()) return;

    updateForEmptyContent(window);
  }

  private DefaultActionGroup createToolbar(@NotNull ToolWindow toolWindow,
                                           @NotNull FlutterApp app,
                                           InspectorService inspectorService) {
    final DefaultActionGroup toolbarGroup = new DefaultActionGroup();
    final PerAppState state = getOrCreateStateForApp(app);

    if (inspectorService != null) {
      toolbarGroup.addSeparator();
      toolbarGroup.add(state.registerAction(new ForceRefreshAction(app, inspectorService)));
    }
    toolbarGroup.addSeparator();
    toolbarGroup.add(state.registerAction(new PerformanceOverlayAction(app)));
    toolbarGroup.addSeparator();
    toolbarGroup.add(state.registerAction(new DebugPaintAction(app)));
    toolbarGroup.add(state.registerAction(new ShowPaintBaselinesAction(app, true)));
    toolbarGroup.addSeparator();
    toolbarGroup.add(state.registerAction(new TimeDilationAction(app, true)));
    toolbarGroup.addSeparator();
    toolbarGroup.add(new TogglePlatformAction(app, getOrCreateStateForApp(app)));

    final FlutterViewAction selectModeAction = state.registerAction(new ToggleSelectWidgetMode(app));
    final FlutterViewAction legacySelectModeAction = state.registerAction(new ToggleOnDeviceWidgetInspector(app));
    final FlutterViewAction[] currentExtension = {null};

    assert (app.getVMServiceManager() != null);
    app.getVMServiceManager().hasServiceExtension(ServiceExtensions.enableOnDeviceInspector.getExtension(), (hasExtension) -> {
      if (toolWindow.isDisposed() || myProject.isDisposed()) return;

      final FlutterViewAction nextExtension = hasExtension ? selectModeAction : legacySelectModeAction;
      if (currentExtension[0] != nextExtension) {
        if (currentExtension[0] != null) {
          toolbarGroup.remove(currentExtension[0]);
        }
        toolbarGroup.add(nextExtension, Constraints.FIRST);
        currentExtension[0] = nextExtension;
      }
    });

    return toolbarGroup;
  }

  private PerAppState getStateForApp(FlutterApp app) {
    return perAppViewState.get(app);
  }

  private PerAppState getOrCreateStateForApp(FlutterApp app) {
    return perAppViewState.computeIfAbsent(app, k -> new PerAppState());
  }

  private void addBrowserInspectorViewContent(FlutterApp app,
                                              @Nullable InspectorService inspectorService,
                                              ToolWindow toolWindow,
                                              boolean isEmbedded,
                                              DevToolsInstance devToolsInstance) {
    assert(SwingUtilities.isEventDispatchThread());

    final ContentManager contentManager = toolWindow.getContentManager();

    final FlutterDevice device = app.device();
    final List<FlutterDevice> existingDevices = new ArrayList<>();
    for (FlutterApp otherApp : perAppViewState.keySet()) {
      existingDevices.add(otherApp.device());
    }
    final String tabName = device.getUniqueName(existingDevices);

    if (emptyContent != null) {
      contentManager.removeContent(emptyContent, true);
      emptyContent = null;
    }

    final String browserUrl = app.getConnector().getBrowserUrl();
    FlutterSdk flutterSdk = FlutterSdk.getFlutterSdk(app.getProject());
    FlutterSdkVersion flutterSdkVersion = flutterSdk == null ? null : flutterSdk.getVersion();

    if (isEmbedded) {
      final String color = ColorUtil.toHex(UIUtil.getEditorPaneBackground());
      final DevToolsUrl devToolsUrl = new DevToolsUrl(
        devToolsInstance.host,
        devToolsInstance.port,
        browserUrl,
        "inspector",
        true,
        color,
        UIUtil.getFontSize(UIUtil.FontSize.NORMAL),
        flutterSdkVersion,
        WorkspaceCache.getInstance(app.getProject())
      );

      //noinspection CodeBlock2Expr
      ApplicationManager.getApplication().invokeLater(() -> {
        embeddedBrowserOptional().ifPresent(embeddedBrowser -> embeddedBrowser.openPanel(contentManager, tabName, devToolsUrl, () -> {
          // If the embedded browser doesn't work, offer a link to open in the regular browser.
          final List<LabelInput> inputs = Arrays.asList(
            new LabelInput("The embedded browser failed to load."),
            openDevToolsLabel(app, inspectorService, toolWindow)
          );
          presentClickableLabel(toolWindow, inputs);
        }));
      });

      if (!busSubscribed) {
        busConnection.subscribe(EditorColorsManager.TOPIC, (EditorColorsListener)scheme ->
          embeddedBrowserOptional()
            .ifPresent(embeddedBrowser -> embeddedBrowser.updateColor(ColorUtil.toHex(UIUtil.getEditorPaneBackground())))
        );
        busConnection.subscribe(UISettingsListener.TOPIC, (UISettingsListener)scheme ->
          embeddedBrowserOptional()
            .ifPresent(embeddedBrowser -> embeddedBrowser.updateFontSize(UIUtil.getFontSize(UIUtil.FontSize.NORMAL)))
        );
        busSubscribed = true;
      }
    } else {
      BrowserLauncher.getInstance().browse(
        (new DevToolsUrl(devToolsInstance.host, devToolsInstance.port, browserUrl, "inspector", false, null, null,
                         flutterSdkVersion, WorkspaceCache.getInstance(app.getProject())).getUrlString()),
        null
      );
      presentLabel(toolWindow, "DevTools inspector has been opened in the browser.");
    }
  }

  private Optional<EmbeddedBrowser> embeddedBrowserOptional() {
    if (myProject.isDisposed()) {
      return Optional.empty();
    }
    return Optional.ofNullable(FlutterUtils.embeddedBrowser(myProject));
  }

  private void addInspectorViewContent(FlutterApp app, @Nullable InspectorService inspectorService, ToolWindow toolWindow) {
    final ContentManager contentManager = toolWindow.getContentManager();
    final SimpleToolWindowPanel toolWindowPanel = new SimpleToolWindowPanel(true);
    // TODO: Don't switch to JBRunnerTabs(Project, Disposable) until 2020.1.
    final JBRunnerTabs runnerTabs = new JBRunnerTabs(myProject, ActionManager.getInstance(), IdeFocusManager.getInstance(myProject), this);
    runnerTabs.setSelectionChangeHandler(this::onTabSelectionChange);
    final JPanel tabContainer = new JPanel(new BorderLayout());

    final String tabName;
    final FlutterDevice device = app.device();
    if (device == null) {
      tabName = app.getProject().getName();
    }
    else {
      final List<FlutterDevice> existingDevices = new ArrayList<>();
      for (FlutterApp otherApp : perAppViewState.keySet()) {
        existingDevices.add(otherApp.device());
      }
      tabName = device.getUniqueName(existingDevices);
    }

    final Content content = contentManager.getFactory().createContent(null, tabName, false);
    tabContainer.add(runnerTabs.getComponent(), BorderLayout.CENTER);
    content.setComponent(tabContainer);
    content.putUserData(ToolWindow.SHOW_CONTENT_ICON, Boolean.TRUE);
    content.setIcon(FlutterIcons.Phone);
    contentManager.addContent(content);

    if (emptyContent != null) {
      contentManager.removeContent(emptyContent, true);
      emptyContent = null;
    }

    contentManager.setSelectedContent(content);

    final PerAppState state = getOrCreateStateForApp(app);
    assert (state.content == null);
    state.content = content;

    final DefaultActionGroup toolbarGroup = createToolbar(toolWindow, app, inspectorService);
    toolWindowPanel.setToolbar(ActionManager.getInstance().createActionToolbar("FlutterViewToolbar", toolbarGroup, true).getComponent());

    toolbarGroup.add(new OverflowAction(getOrCreateStateForApp(app), this, app));

    final ActionToolbar toolbar = ActionManager.getInstance().createActionToolbar("InspectorToolbar", toolbarGroup, true);
    final JComponent toolbarComponent = toolbar.getComponent();
    toolbarComponent.setBorder(IdeBorderFactory.createBorder(SideBorder.BOTTOM));
    tabContainer.add(toolbarComponent, BorderLayout.NORTH);

    final boolean debugConnectionAvailable = app.getLaunchMode().supportsDebugConnection();
    final boolean hasInspectorService = inspectorService != null;

    // If the inspector is available (non-release mode), then show it.
    if (debugConnectionAvailable) {
      if (hasInspectorService) {
        final boolean detailsSummaryViewSupported = inspectorService.isDetailsSummaryViewSupported();
        addInspectorPanel(WIDGET_TAB_LABEL, runnerTabs, state, InspectorService.FlutterTreeType.widget, app, inspectorService, toolWindow,
                          toolbarGroup, true, detailsSummaryViewSupported);
        addInspectorPanel(RENDER_TAB_LABEL, runnerTabs, state, InspectorService.FlutterTreeType.renderObject, app, inspectorService,
                          toolWindow, toolbarGroup, false, false);
      }
      else {
        // If in profile mode, add disabled tabs for the inspector.
        addDisabledTab(WIDGET_TAB_LABEL, runnerTabs, toolbarGroup);
        addDisabledTab(RENDER_TAB_LABEL, runnerTabs, toolbarGroup);
      }
    }
    else {
      // Add a message about the inspector not being available in release mode.
      final JBLabel label = new JBLabel("Inspector not available in release mode", SwingConstants.CENTER);
      label.setForeground(UIUtil.getLabelDisabledForeground());
      tabContainer.add(label, BorderLayout.CENTER);
    }
  }

  private ActionCallback onTabSelectionChange(TabInfo info, boolean requestFocus, @NotNull ActiveRunnable doChangeSelection) {
    if (info.getComponent() instanceof InspectorTabPanel) {
      final InspectorTabPanel panel = (InspectorTabPanel)info.getComponent();
      panel.setVisibleToUser(true);
    }

    final TabInfo previous = info.getPreviousSelection();

    // Track analytics for explicit inspector tab selections.
    // (The initial selection will have no previous, so we filter that out.)
    if (previous != null) {
      FlutterInitializer.getAnalytics().sendScreenView(
        FlutterView.TOOL_WINDOW_ID.toLowerCase() + "/" + info.getText().toLowerCase());
    }

    if (previous != null && previous.getComponent() instanceof InspectorTabPanel) {
      final InspectorTabPanel panel = (InspectorTabPanel)previous.getComponent();
      panel.setVisibleToUser(false);
    }
    return doChangeSelection.run();
  }

  private void addInspectorPanel(String displayName,
                                 JBRunnerTabs tabs,
                                 PerAppState state,
                                 InspectorService.FlutterTreeType treeType,
                                 FlutterApp app,
                                 InspectorService inspectorService,
                                 @NotNull ToolWindow toolWindow,
                                 DefaultActionGroup toolbarGroup,
                                 boolean selectedTab,
                                 boolean useSummaryTree) {
    final InspectorPanel inspectorPanel = new InspectorPanel(
      this,
      app,
      inspectorService,
      app::isSessionActive,
      treeType,
      useSummaryTree,
      // TODO(jacobr): support the summary tree view for the RenderObject
      // tree instead of forcing the legacy view for the RenderObject tree.
      treeType != InspectorService.FlutterTreeType.widget || !inspectorService.isDetailsSummaryViewSupported(),
      shouldAutoHorizontalScroll,
      highlightNodesShownInBothTrees
    );
    final TabInfo tabInfo = new TabInfo(inspectorPanel)
      .append(displayName, SimpleTextAttributes.REGULAR_ATTRIBUTES);
    tabs.addTab(tabInfo);
    state.inspectorPanels.add(inspectorPanel);
    if (selectedTab) {
      tabs.select(tabInfo, false);
    }
  }

  private void addDisabledTab(String displayName,
                              JBRunnerTabs runnerTabs,
                              DefaultActionGroup toolbarGroup) {
    final JPanel panel = new JPanel(new BorderLayout());
    final JBLabel label = new JBLabel("Widget info not available in profile mode", SwingConstants.CENTER);
    label.setForeground(UIUtil.getLabelDisabledForeground());
    panel.add(label, BorderLayout.CENTER);

    final TabInfo tabInfo = new TabInfo(panel)
      .append(displayName, SimpleTextAttributes.GRAYED_ATTRIBUTES);
    runnerTabs.addTab(tabInfo);
  }

  /**
   * Called when a debug connection starts.
   */
  public void debugActive(@NotNull FlutterViewMessages.FlutterDebugEvent event) {
    final FlutterApp app = event.app;
    if (app.getFlutterDebugProcess() == null || app.getFlutterDebugProcess().getInspectorService() == null) {
      return;
    }

    if (app.getMode().isProfiling() || app.getLaunchMode().isProfiling()) {
      ApplicationManager.getApplication().invokeLater(() -> debugActiveHelper(app, null));
    }
    else {
      AsyncUtils.whenCompleteUiThread(
        app.getFlutterDebugProcess().getInspectorService(),
        (InspectorService inspectorService, Throwable throwable) -> {
          if (throwable != null) {
            FlutterUtils.warn(LOG, throwable);
            return;
          }

          debugActiveHelper(app, inspectorService);
        });
    }
  }

  protected void handleJxBrowserInstalled(FlutterApp app, InspectorService inspectorService, ToolWindow toolWindow) {
    presentDevTools(app, inspectorService, toolWindow, true);
  }

  private void presentDevTools(FlutterApp app, InspectorService inspectorService, ToolWindow toolWindow, boolean isEmbedded) {
    verifyEventDispatchThread();

    devToolsInstallCount += 1;
    presentLabel(toolWindow, getInstallingDevtoolsLabel());

    openInspectorWithDevTools(app, inspectorService, toolWindow, isEmbedded);

    setUpToolWindowListener(app, inspectorService, toolWindow, isEmbedded);
  }

  @VisibleForTesting
  protected void verifyEventDispatchThread() {
    assert(SwingUtilities.isEventDispatchThread());
  }

  @VisibleForTesting
  protected void setUpToolWindowListener(FlutterApp app, InspectorService inspectorService, ToolWindow toolWindow, boolean isEmbedded) {
    if (this.toolWindowListener == null) {
      this.toolWindowListener = new FlutterViewToolWindowManagerListener(myProject, toolWindow);
    }
    this.toolWindowListener.updateOnWindowOpen(() -> {
      devToolsInstallCount += 1;
      presentLabel(toolWindow, getInstallingDevtoolsLabel());
      openInspectorWithDevTools(app, inspectorService, toolWindow, isEmbedded, true);
    });
  }

  private String getInstallingDevtoolsLabel() {
    return "<html><body style=\"text-align: center;\">" +
           FlutterBundle.message("flutter.devtools.installing", devToolsInstallCount) + "</body></html>";
  }

  @VisibleForTesting
  protected void openInspectorWithDevTools(FlutterApp app, InspectorService inspectorService, ToolWindow toolWindow, boolean isEmbedded) {
    openInspectorWithDevTools(app, inspectorService, toolWindow, isEmbedded, false);
  }

  private void openInspectorWithDevTools(FlutterApp app,
                                           InspectorService inspectorService,
                                           ToolWindow toolWindow,
                                           boolean isEmbedded,
                                           boolean forceDevToolsRestart) {
    AsyncUtils.whenCompleteUiThread(
      forceDevToolsRestart
      ? DevToolsService.getInstance(myProject).getDevToolsInstanceWithForcedRestart()
      : DevToolsService.getInstance(myProject).getDevToolsInstance(),
      (instance, error) -> {
        // Skip displaying if the project has been closed.
        if (!myProject.isOpen()) {
          return;
        }

        // TODO(helinx): Restart DevTools server if there's an error.
        if (error != null) {
          LOG.error(error);
          presentLabel(toolWindow, DEVTOOLS_FAILED_LABEL);
          return;
        }

        if (instance == null) {
          presentLabel(toolWindow, DEVTOOLS_FAILED_LABEL);
          return;
        }

        addBrowserInspectorViewContent(app, inspectorService, toolWindow, isEmbedded, instance);
      }
    );
  }

  private LabelInput openDevToolsLabel(FlutterApp app, InspectorService inspectorService, ToolWindow toolWindow) {
    return new LabelInput("Open DevTools in the browser?", (linkLabel, data) -> {
      presentDevTools(app, inspectorService, toolWindow, false);
    });
  }

  protected void handleJxBrowserInstallationInProgress(FlutterApp app, InspectorService inspectorService, ToolWindow toolWindow) {
    presentOpenDevToolsOptionWithMessage(app, inspectorService, toolWindow, INSTALLATION_IN_PROGRESS_LABEL);

    if (jxBrowserManager.getStatus().equals(JxBrowserStatus.INSTALLED)) {
      handleJxBrowserInstalled(app, inspectorService, toolWindow);
    }
    else {
      startJxBrowserInstallationWaitingThread(app, inspectorService, toolWindow);
    }
  }

  protected void startJxBrowserInstallationWaitingThread(FlutterApp app, InspectorService inspectorService, ToolWindow toolWindow) {
    ApplicationManager.getApplication().executeOnPooledThread(() -> {
      waitForJxBrowserInstallation(app, inspectorService, toolWindow);
    });
  }

  protected void waitForJxBrowserInstallation(FlutterApp app, InspectorService inspectorService, ToolWindow toolWindow) {
    try {
      final JxBrowserStatus newStatus = jxBrowserManager.waitForInstallation(INSTALLATION_WAIT_LIMIT_SECONDS);

      handleUpdatedJxBrowserStatusOnEventThread(app, inspectorService, toolWindow, newStatus);
    }
    catch (TimeoutException e) {
      presentOpenDevToolsOptionWithMessage(app, inspectorService, toolWindow, INSTALLATION_TIMED_OUT_LABEL);

      FlutterInitializer.getAnalytics().sendEvent(JxBrowserManager.ANALYTICS_CATEGORY, "timedOut");
    }
  }

  protected void handleUpdatedJxBrowserStatusOnEventThread(
          FlutterApp app,
          InspectorService inspectorService,
          ToolWindow toolWindow,
          JxBrowserStatus jxBrowserStatus
  ) {
    AsyncUtils.invokeLater(() -> handleUpdatedJxBrowserStatus(app, inspectorService, toolWindow, jxBrowserStatus));
  }

  protected void handleUpdatedJxBrowserStatus(
          FlutterApp app,
          InspectorService inspectorService,
          ToolWindow toolWindow,
          JxBrowserStatus jxBrowserStatus
  ) {
    if (jxBrowserStatus.equals(JxBrowserStatus.INSTALLED)) {
      handleJxBrowserInstalled(app, inspectorService, toolWindow);
    } else if (jxBrowserStatus.equals(JxBrowserStatus.INSTALLATION_FAILED)) {
      handleJxBrowserInstallationFailed(app, inspectorService, toolWindow);
    } else {
      // newStatus can be null if installation is interrupted or stopped for another reason.
      presentOpenDevToolsOptionWithMessage(app, inspectorService, toolWindow, INSTALLATION_WAIT_FAILED);
    }
  }

  protected void handleJxBrowserInstallationFailed(FlutterApp app, InspectorService inspectorService, ToolWindow toolWindow) {
    final List<LabelInput> inputs = new ArrayList<>();
    final LabelInput openDevToolsLabel = openDevToolsLabel(app, inspectorService, toolWindow);

    final InstallationFailedReason latestFailureReason = jxBrowserManager.getLatestFailureReason();

    if (!jxBrowserUtils.licenseIsSet()) {
      // If the license isn't available, allow the user to open the equivalent page in a non-embedded browser window.
      inputs.add(new LabelInput("The JxBrowser license could not be found."));
      inputs.add(openDevToolsLabel);
    } else if (latestFailureReason != null && latestFailureReason.failureType.equals(FailureType.SYSTEM_INCOMPATIBLE)) {
      // If we know the system is incompatible, skip retry link and offer to open in browser.
      inputs.add(new LabelInput(latestFailureReason.detail));
      inputs.add(openDevToolsLabel);
    }
    else {
      // Allow the user to manually restart or open the equivalent page in a non-embedded browser window.
      inputs.add(new LabelInput("JxBrowser installation failed."));
      inputs.add(new LabelInput("Retry installation?", (linkLabel, data) -> {
        jxBrowserManager.retryFromFailed(app.getProject());
        handleJxBrowserInstallationInProgress(app, inspectorService, toolWindow);
      }));
      inputs.add(openDevToolsLabel);
    }

    presentClickableLabel(toolWindow, inputs);
  }

  protected void presentLabel(ToolWindow toolWindow, String text) {
    final JBLabel label = new JBLabel(text, SwingConstants.CENTER);
    label.setForeground(UIUtil.getLabelDisabledForeground());
    replacePanelLabel(toolWindow, label);
  }

  protected void presentClickableLabel(ToolWindow toolWindow, List<LabelInput> labels) {
    final JPanel panel = new JPanel(new GridLayout(0, 1));

    for (LabelInput input : labels) {
      if (input.listener == null) {
        final JLabel descriptionLabel = new JLabel("<html>" + input.text + "</html>");
        descriptionLabel.setBorder(JBUI.Borders.empty(5));
        descriptionLabel.setHorizontalAlignment(SwingConstants.CENTER);
        panel.add(descriptionLabel, BorderLayout.NORTH);
      } else {
        final LinkLabel<String> linkLabel = new LinkLabel<>("<html>" + input.text + "</html>", null);
        linkLabel.setBorder(JBUI.Borders.empty(5));
        linkLabel.setListener(input.listener, null);
        linkLabel.setHorizontalAlignment(SwingConstants.CENTER);
        panel.add(linkLabel, BorderLayout.SOUTH);
      }
    }

    final JPanel center = new JPanel(new VerticalFlowLayout(VerticalFlowLayout.CENTER));
    center.add(panel);
    replacePanelLabel(toolWindow, center);
  }

  protected void presentOpenDevToolsOptionWithMessage(FlutterApp app,
                                                      InspectorService inspectorService,
                                                      ToolWindow toolWindow,
                                                      String message) {
    final List<LabelInput> inputs = new ArrayList<>();
    inputs.add(new LabelInput(message));
    inputs.add(openDevToolsLabel(app, inspectorService, toolWindow));
    presentClickableLabel(toolWindow, inputs);
  }

  private void replacePanelLabel(ToolWindow toolWindow, JComponent label) {
    ApplicationManager.getApplication().invokeLater(() -> {
      final ContentManager contentManager = toolWindow.getContentManager();
      if (contentManager.isDisposed()) {
        return;
      }

      final JPanel panel = new JPanel(new BorderLayout());
      panel.add(label, BorderLayout.CENTER);
      final Content content = contentManager.getFactory().createContent(panel, null, false);
      contentManager.addContent(content);
    });
  }

  private void debugActiveHelper(FlutterApp app, @Nullable InspectorService inspectorService) {
    final ToolWindowManager toolWindowManager = ToolWindowManager.getInstance(myProject);
    if (!(toolWindowManager instanceof ToolWindowManagerEx)) {
      return;
    }

    final ToolWindow toolWindow = toolWindowManager.getToolWindow(FlutterView.TOOL_WINDOW_ID);
    if (toolWindow == null) {
      return;
    }

    if (toolWindow.isAvailable()) {
      updateToolWindowVisibility(toolWindow);
    }
    else {
      toolWindow.setAvailable(true, () -> {
        updateToolWindowVisibility(toolWindow);
      });
    }

    if (emptyContent != null) {
      final ContentManager contentManager = toolWindow.getContentManager();
      contentManager.removeContent(emptyContent, true);
      emptyContent = null;
    }

    toolWindow.setIcon(ExecutionUtil.getLiveIndicator(FlutterIcons.Flutter_13));

    if (toolWindow.isVisible()) {
      displayEmbeddedBrowser(app, inspectorService, toolWindow);
    }
    else {
      if (toolWindowListener == null) {
        toolWindowListener = new FlutterViewToolWindowManagerListener(myProject, toolWindow);
      }
      // If the window isn't visible yet, only executed embedded browser steps when it becomes visible.
      toolWindowListener.updateOnWindowFirstVisible(() -> {
        displayEmbeddedBrowser(app, inspectorService, toolWindow);
      });
    }
  }

  private void displayEmbeddedBrowser(FlutterApp app, InspectorService inspectorService, ToolWindow toolWindow) {
    if (FlutterSettings.getInstance().isEnableJcefBrowser()) {
      presentDevTools(app, inspectorService, toolWindow, true);
    } else {
      displayEmbeddedBrowserIfJxBrowser(app, inspectorService, toolWindow);
    }
  }

  private void displayEmbeddedBrowserIfJxBrowser(FlutterApp app, InspectorService inspectorService, ToolWindow toolWindow) {
    final JxBrowserManager manager = jxBrowserManager;
    final JxBrowserStatus jxBrowserStatus = manager.getStatus();

    if (jxBrowserStatus.equals(JxBrowserStatus.INSTALLED)) {
      handleJxBrowserInstalled(app, inspectorService, toolWindow);
    }
    else if (jxBrowserStatus.equals(JxBrowserStatus.INSTALLATION_IN_PROGRESS)) {
      handleJxBrowserInstallationInProgress(app, inspectorService, toolWindow);
    }
    else if (jxBrowserStatus.equals(JxBrowserStatus.INSTALLATION_FAILED)) {
      handleJxBrowserInstallationFailed(app, inspectorService, toolWindow);
    } else if (jxBrowserStatus.equals(JxBrowserStatus.NOT_INSTALLED) || jxBrowserStatus.equals(JxBrowserStatus.INSTALLATION_SKIPPED)) {
      manager.setUp(myProject);
      handleJxBrowserInstallationInProgress(app, inspectorService, toolWindow);
    }
  }

  private void updateForEmptyContent(ToolWindow toolWindow) {
    // There's a possible race here where the tool window gets disposed while we're displaying contents.
    if (toolWindow.isDisposed()) {
      return;
    }

    toolWindow.setIcon(FlutterIcons.Flutter_13);

    // Display a 'No running applications' message.
    final ContentManager contentManager = toolWindow.getContentManager();
    final JPanel panel = new JPanel(new BorderLayout());
    final JBLabel label = new JBLabel("No running applications", SwingConstants.CENTER);
    label.setForeground(UIUtil.getLabelDisabledForeground());
    panel.add(label, BorderLayout.CENTER);
    emptyContent = contentManager.getFactory().createContent(panel, null, false);
    contentManager.addContent(emptyContent);
  }

  private static void listenForRenderTreeActivations(@NotNull ToolWindow toolWindow) {
    final ContentManager contentManager = toolWindow.getContentManager();
    // TODO: Don't switch to ContentManagerListener until 2020.1.
    contentManager.addContentManagerListener(new ContentManagerAdapter() {
      @Override
      public void selectionChanged(@NotNull ContentManagerEvent event) {
        final ContentManagerEvent.ContentOperation operation = event.getOperation();
        if (operation == ContentManagerEvent.ContentOperation.add) {
          final String name = event.getContent().getTabName();
          if (Objects.equals(name, RENDER_TAB_LABEL)) {
            FlutterInitializer.getAnalytics().sendEvent("inspector", "renderTreeSelected");
          }
          else if (Objects.equals(name, WIDGET_TAB_LABEL)) {
            FlutterInitializer.getAnalytics().sendEvent("inspector", "widgetTreeSelected");
          }
        }
      }
    });
  }

  private void handleFlutterFrame(FlutterApp app) {
    final PerAppState state = getStateForApp(app);
    if (state != null && state.sendRestartNotificationOnNextFrame) {
      state.sendRestartNotificationOnNextFrame = false;
      notifyActionsOnRestart(app);
    }
  }

  private void notifyActionsAppStarted(FlutterApp app) {
    final PerAppState state = getStateForApp(app);
    if (state == null) {
      return;
    }
    for (FlutterViewAction action : state.flutterViewActions) {
      action.handleAppStarted();
    }
  }

  private void notifyActionsOnRestart(FlutterApp app) {
    final PerAppState state = getStateForApp(app);
    if (state == null) {
      return;
    }
    for (FlutterViewAction action : state.flutterViewActions) {
      action.handleAppRestarted();
    }
  }

  private void notifyActionsAppStopped(FlutterApp app) {
    final PerAppState state = getStateForApp(app);
    if (state == null) {
      return;
    }
    state.sendRestartNotificationOnNextFrame = false;
  }

  private void onAppChanged(FlutterApp app) {
    if (myProject.isDisposed()) {
      return;
    }

    final ToolWindow toolWindow = ToolWindowManager.getInstance(myProject).getToolWindow(TOOL_WINDOW_ID);
    if (toolWindow == null) {
      return;
    }

    if (perAppViewState.isEmpty()) {
      notifyActionsAppStopped(app);
    }
    else {
      notifyActionsAppStarted(app);
    }

    final PerAppState state = getStateForApp(app);
    if (state != null) {
      for (InspectorPanel inspectorPanel : state.inspectorPanels) {
        inspectorPanel.onAppChanged();
      }
    }
  }

  private void updateToolWindowVisibility(ToolWindow flutterToolWindow) {
    if (flutterToolWindow.isVisible()) {
      return;
    }

    if (FlutterSettings.getInstance().isOpenInspectorOnAppLaunch()) {
      flutterToolWindow.show(null);
    }
  }
}

class FlutterViewDevToolsAction extends FlutterViewAction {
  private static final Logger LOG = Logger.getInstance(FlutterViewDevToolsAction.class);
  FlutterViewDevToolsAction(@NotNull FlutterApp app) {
    super(app, "Open DevTools", "Open Dart DevTools", FlutterIcons.Dart_16);
  }

  @Override
  public void perform(AnActionEvent event) {
    if (app.isSessionActive()) {
      final String urlString = app.getConnector().getBrowserUrl();
      if (urlString == null) {
        return;
      }

      AsyncUtils.whenCompleteUiThread(DevToolsService.getInstance(app.getProject()).getDevToolsInstance(), (instance, ex) -> {
        if (app.getProject().isDisposed()) {
          return;
        }

        if (ex != null) {
          LOG.error(ex);
          return;
        }

        FlutterSdk flutterSdk = FlutterSdk.getFlutterSdk(app.getProject());
        BrowserLauncher.getInstance().browse(
          (new DevToolsUrl(instance.host, instance.port, urlString, null, false, null, null, flutterSdk == null ? null : flutterSdk.getVersion(), WorkspaceCache.getInstance(app.getProject())).getUrlString()),
          null
        );
      });
    }
  }
}

class RepaintRainbowAction extends FlutterViewToggleableAction {
  RepaintRainbowAction(@NotNull FlutterApp app) {
    super(app, FlutterIcons.RepaintRainbow, ServiceExtensions.repaintRainbow);
  }
}

class ToggleSelectWidgetMode extends FlutterViewToggleableAction {
  ToggleSelectWidgetMode(@NotNull FlutterApp app) {
    super(app, AllIcons.General.Locate, ServiceExtensions.toggleSelectWidgetMode);
  }

  @Override
  protected void perform(AnActionEvent event) {
    super.perform(event);

    if (app.isSessionActive()) {
      // If toggling inspect mode on, bring the app's device to the foreground.
      if (isSelected()) {
        final FlutterDevice device = app.device();
        if (device != null) {
          device.bringToFront();
        }
      }
    }
  }

  @Override
  public void handleAppRestarted() {
    if (isSelected()) {
      setSelected(null, false);
    }
  }
}

class ToggleOnDeviceWidgetInspector extends FlutterViewToggleableAction {
  ToggleOnDeviceWidgetInspector(@NotNull FlutterApp app) {
    super(app, AllIcons.General.Locate, ServiceExtensions.toggleOnDeviceWidgetInspector);
  }

  @Override
  protected void perform(AnActionEvent event) {
    super.perform(event);

    if (app.isSessionActive()) {
      // If toggling inspect mode on, bring the app's device to the foreground.
      if (isSelected()) {
        final FlutterDevice device = app.device();
        if (device != null) {
          device.bringToFront();
        }
      }
    }
  }

  @Override
  public void handleAppRestarted() {
    if (isSelected()) {
      setSelected(null, false);
    }
  }
}

class ForceRefreshAction extends FlutterViewAction {
  final @NotNull InspectorService inspectorService;

  private boolean enabled = true;

  ForceRefreshAction(@NotNull FlutterApp app, @NotNull InspectorService inspectorService) {
    super(app, "Refresh Widget Info", "Refresh Widget Info", AllIcons.Actions.ForceRefresh);

    this.inspectorService = inspectorService;
  }

  private void setEnabled(AnActionEvent event, boolean enabled) {
    this.enabled = enabled;

    update(event);
  }

  @Override
  protected void perform(final AnActionEvent event) {
    if (app.isSessionActive()) {
      setEnabled(event, false);

      final CompletableFuture<?> future = inspectorService.forceRefresh();

      AsyncUtils.whenCompleteUiThread(future, (o, throwable) -> setEnabled(event, true));
    }
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    e.getPresentation().setEnabled(app.isSessionActive() && enabled);
  }
}

class ShowDebugBannerAction extends FlutterViewToggleableAction {
  ShowDebugBannerAction(@NotNull FlutterApp app) {
    super(app, FlutterIcons.DebugBanner, ServiceExtensions.debugAllowBanner);
  }
}

class AutoHorizontalScrollAction extends FlutterViewLocalToggleableAction {
  AutoHorizontalScrollAction(@NotNull FlutterApp app, EventStream<Boolean> value) {
    super(app, "Auto horizontal scroll", value);
  }
}

class HighlightNodesShownInBothTrees extends FlutterViewLocalToggleableAction {
  HighlightNodesShownInBothTrees(@NotNull FlutterApp app, EventStream<Boolean> value) {
    super(app, "Highlight nodes displayed in both trees", value);
  }
}

class OverflowAction extends ToolbarComboBoxAction implements RightAlignedToolbarAction {
  private final @NotNull FlutterApp app;
  private final DefaultActionGroup myActionGroup;

  public OverflowAction(@NotNull AppState appState, @NotNull FlutterView view, @NotNull FlutterApp app) {
    super();

    this.app = app;
    myActionGroup = createPopupActionGroup(appState, view, app);
  }

  @NotNull
  @Override
  protected DefaultActionGroup createPopupActionGroup(JComponent button) {
    return myActionGroup;
  }

  @Override
  public final void update(AnActionEvent e) {
    e.getPresentation().setText("More Actions");
    e.getPresentation().setEnabled(app.isSessionActive());
  }

  private static DefaultActionGroup createPopupActionGroup(AppState appState, FlutterView view, FlutterApp app) {
    final DefaultActionGroup group = new DefaultActionGroup();

    group.add(appState.registerAction(new RepaintRainbowAction(app)));
    group.addSeparator();
    group.add(appState.registerAction(new ShowDebugBannerAction(app)));
    group.addSeparator();
    group.add(appState.registerAction(new AutoHorizontalScrollAction(app, view.shouldAutoHorizontalScroll)));
    group.add(appState.registerAction(new HighlightNodesShownInBothTrees(app, view.highlightNodesShownInBothTrees)));
    group.addSeparator();
    group.add(appState.registerAction(new FlutterViewDevToolsAction(app)));

    return group;
  }
}

class AppState {
  ArrayList<FlutterViewAction> flutterViewActions = new ArrayList<>();
  Content content;

  FlutterViewAction registerAction(FlutterViewAction action) {
    flutterViewActions.add(action);
    return action;
  }
}

class LabelInput {
  String text;
  LinkListener<String> listener;

  public LabelInput(String text) {
    this(text, null);
  }

  public LabelInput(String text, LinkListener<String> listener) {
    this.text = text;
    this.listener = listener;
  }
}

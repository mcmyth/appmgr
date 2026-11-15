package com.mcmyth.appmgr;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.animation.PathInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.AbsListView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.view.OneShotPreDrawListener;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class MainActivity extends Activity {
    private static final int USER = 0;
    private static final int SYSTEM = 1;
    private static final int BACKUP = 2;
    private static final int PICK_DIRECTORY = 10;
    private static final int ALLOW_INSTALL = 11;
    private static final PathInterpolator COLLAPSE_EASING = new PathInterpolator(0.32f, 0, 0.67f, 1);
    private static final String[] TABS = {"用户程序", "系统程序", "备份程序"};

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AppCatalog catalog = new AppCatalog();
    private final Map<String, AppCatalog.App> installed = new HashMap<>();
    private final Map<String, AppCatalog.App> pendingPackageUpdates = new LinkedHashMap<>();
    private final ListPage[] pages = new ListPage[TABS.length];
    private final TextView[] tabs = new TextView[3];
    private final LruCache<String, Drawable.ConstantState> appIcons = new LruCache<>(96);
    private final SimpleDateFormat shortDate = new SimpleDateFormat("yyyy/MM/dd", Locale.ROOT);
    private final SimpleDateFormat fullDate = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.ROOT);
    private List<AppCatalog.App> apps = new ArrayList<>();
    private BackupStore.Snapshot backups;
    private String backupError;
    private AppViews ui;
    private SharedPreferences preferences;
    private BackupStore store;
    private LinearLayout root;
    private LinearLayout mainPage;
    private FrameLayout pageHost;
    private LinearLayout toolbar;
    private FrameLayout tabTrack;
    private View tabIndicator;
    private TextView letterBubble;
    private final Runnable fadeLetter = this::fadeLetterBubble;
    private ViewPager2 pager;
    private FrameLayout drawer;
    private LinearLayout drawerPanel;
    private View drawerShade;
    private ProgressBar progress;
    private ProgressBar mainProgress;
    private CatalogProgressCard catalogProgress;
    private EditText search;
    private int tab;
    private boolean searching;
    private boolean detail;
    private boolean loading;
    private boolean refreshPending;
    private boolean operating;
    private boolean closingDrawer;
    private int systemBackInset;
    private String query = "";
    private int request;
    private BackupStore.Entry pendingInstall;
    private String detailKey;

    private final BroadcastReceiver changes = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Uri data = intent.getData();
            if (data == null) return;
            if (!Intent.ACTION_PACKAGE_REPLACED.equals(action)
                    && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
            String packageName = data.getSchemeSpecificPart();
            if (packageName != null && !packageName.isEmpty()) updatePackage(packageName);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new AppViews(this);
        preferences = getSharedPreferences("appmgr", MODE_PRIVATE);
        store = new BackupStore(this, preferences);
        if (state != null) {
            tab = state.getInt("tab", USER);
            query = state.getString("query", "");
            searching = state.getBoolean("searching");
        }
        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_REPLACED);
        packages.addDataScheme("package");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(changes, packages, RECEIVER_EXPORTED);
        else registerReceiver(changes, packages);
        refresh(false);
        showMain();
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("tab", tab);
        out.putString("query", query);
        out.putBoolean("searching", searching);
    }

    @Override protected void onDestroy() {
        unregisterReceiver(changes);
        worker.shutdown();
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (drawer != null) closeDrawer();
        else if (detail) showMain();
        else if (searching) closeSearch();
        else super.onBackPressed();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || pendingPackageUpdates.isEmpty()) return;
        Map<String, AppCatalog.App> changes = new LinkedHashMap<>(pendingPackageUpdates);
        pendingPackageUpdates.clear();
        changes.forEach(this::applyPackageUpdate);
    }

    private LinearLayout page(int topColor) {
        LinearLayout page = ui.column();
        page.setBackgroundColor(AppViews.SURFACE);
        View status = new View(this);
        status.setBackgroundColor(topColor);
        page.addView(status, new LinearLayout.LayoutParams(-1, 0));
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            int top;
            int bottom;
            int left;
            int right;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                systemBackInset = insets.getInsets(WindowInsets.Type.systemGestures()).left;
                top = bars.top;
                bottom = Math.max(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom);
                left = bars.left;
                right = bars.right;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
                left = insets.getSystemWindowInsetLeft();
                right = insets.getSystemWindowInsetRight();
                if (Build.VERSION.SDK_INT >= 29) systemBackInset = insets.getSystemGestureInsets().left;
            }
            if (status.getLayoutParams().height != top) {
                status.getLayoutParams().height = top;
                status.requestLayout();
            }
            page.setPadding(left, 0, right, bottom);
            return insets;
        });
        updateSystemBars(topColor == AppViews.SURFACE);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(AppViews.SURFACE);
        return page;
    }

    private void updateSystemBars(boolean lightStatusBar) {
        int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        if (lightStatusBar) flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private void showMain() {
        if (mainPage != null) {
            if (!detail) return;
            LinearLayout previous = root;
            detail = false;
            detailKey = null;
            root = mainPage;
            progress = mainProgress;
            updateSystemBars(false);
            mainPage.setVisibility(View.VISIBLE);
            mainPage.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            previous.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            ui.enter(mainPage, -ui.dp(16), 0.78f, 230);
            previous.animate().cancel();
            if (AppViews.motionEnabled()) {
                previous.animate().translationX(ui.dp(72)).alpha(0).setDuration(200)
                        .setInterpolator(AppViews.EASE_OUT).withLayer()
                        .withEndAction(() -> pageHost.removeView(previous)).start();
            } else {
                pageHost.removeView(previous);
            }
            return;
        }
        detail = false;
        root = page(AppViews.DARK_BLUE);
        mainPage = root;
        toolbar = ui.row();
        toolbar.setBackgroundColor(AppViews.DARK_BLUE);
        root.addView(toolbar, new LinearLayout.LayoutParams(-1, ui.dp(60)));
        buildToolbar();

        tabTrack = new FrameLayout(this);
        tabTrack.setBackgroundColor(AppViews.DARK_BLUE);
        LinearLayout tabBar = ui.row();
        for (int i = 0; i < TABS.length; i++) {
            int position = i;
            tabs[i] = ui.text(TABS[i], 13, Color.WHITE, true);
            tabs[i].setGravity(Gravity.CENTER);
            tabs[i].setMaxLines(1);
            tabs[i].setEllipsize(TextUtils.TruncateAt.END);
            ui.ripple(tabs[i], true);
            tabs[i].setOnClickListener(v -> selectTab(position));
            tabBar.addView(tabs[i], new LinearLayout.LayoutParams(0, -1, 1));
        }
        tabTrack.addView(tabBar, new FrameLayout.LayoutParams(-1, -1));
        tabIndicator = new View(this);
        tabIndicator.setBackground(ui.rounded(0xFF81DAFF, 4));
        tabTrack.addView(tabIndicator, new FrameLayout.LayoutParams(ui.dp(32), ui.dp(3), Gravity.BOTTOM));
        tabTrack.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft) positionIndicator(tab);
        });
        root.addView(tabTrack, new LinearLayout.LayoutParams(-1, ui.dp(46)));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(AppViews.BLUE));
        root.addView(progress, new LinearLayout.LayoutParams(-1, ui.dp(3)));
        mainProgress = progress;

        for (int i = 0; i < pages.length; i++) pages[i] = new ListPage(i);
        pager = new ViewPager2(this);
        pager.setOffscreenPageLimit(pages.length - 1);
        pager.setAdapter(new PagerAdapter());
        pager.setCurrentItem(tab, false);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override public void onPageScrolled(int position, float offset, int offsetPixels) {
                positionIndicator(position + offset);
            }

            @Override public void onPageSelected(int position) {
                if (position == tab) return;
                tab = position;
                updateTabSelection();
                if (searching) search.setHint(tab == BACKUP ? "搜索名称、包名或文件名" : "搜索名称或包名");
                else buildToolbar();
            }

            @Override public void onPageScrollStateChanged(int state) {
                if (state != ViewPager2.SCROLL_STATE_IDLE) {
                    pages[tab].hideDateImmediately();
                    pages[tab].stopSectionScroll();
                    hideLetterBubble();
                }
            }
        });

        HorizontalSwipeFrame drawerGesture = new HorizontalSwipeFrame(this, new HorizontalSwipeFrame.Swipe() {
            @Override public boolean canDrag(float startX, float delta) {
                return drawer == null && pager.getScrollState() == ViewPager2.SCROLL_STATE_IDLE
                        && startX >= systemBackInset
                        && startX <= Math.max(ui.dp(32), systemBackInset + ui.dp(18))
                        && delta > 0 && !searching;
            }

            @Override public void drag(float delta) {
                if (drawer == null) createDrawer();
                moveDrawer(Math.max(0, delta) / drawerWidth());
            }

            @Override public void finish(float delta, boolean committed) {
                if (committed && delta > 0) animateDrawer(true);
                else closeDrawer();
            }
        });
        drawerGesture.addView(pager, new FrameLayout.LayoutParams(-1, -1));
        root.addView(drawerGesture, new LinearLayout.LayoutParams(-1, 0, 1));

        pageHost = new FrameLayout(this);
        pageHost.setBackgroundColor(AppViews.SURFACE);
        pageHost.addView(root, new FrameLayout.LayoutParams(-1, -1));
        letterBubble = ui.text("", 52, Color.WHITE, true);
        letterBubble.setGravity(Gravity.CENTER);
        letterBubble.setBackground(ui.rounded(0xBB12314C, 27));
        letterBubble.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        letterBubble.setVisibility(View.GONE);
        pageHost.addView(letterBubble, new FrameLayout.LayoutParams(ui.dp(104), ui.dp(104), Gravity.CENTER));
        catalogProgress = new CatalogProgressCard(this, ui);
        FrameLayout.LayoutParams card = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        card.setMargins(ui.dp(24), 0, ui.dp(24), 0);
        pageHost.addView(catalogProgress, card);
        if (loading) catalogProgress.begin(
                tab == BACKUP ? "读取备份列表" : "读取应用列表",
                tab == BACKUP ? "正在准备读取备份…" : "正在获取应用清单…");
        setContentView(pageHost);
        root.requestApplyInsets();
        renderAll(false);
    }

    private void buildToolbar() {
        toolbar.removeAllViews();
        if (searching) {
            toolbar.addView(ui.icon("back", "退出搜索", Color.WHITE, v -> closeSearch()), toolbarButton());
            search = new EditText(this);
            search.setTextColor(Color.WHITE);
            search.setHintTextColor(0xCCFFFFFF);
            search.setTextSize(16);
            search.setSingleLine();
            search.setHint(tab == BACKUP ? "搜索名称、包名或文件名" : "搜索名称或包名");
            search.setBackgroundColor(Color.TRANSPARENT);
            search.setText(query);
            search.setSelectAllOnFocus(false);
            toolbar.addView(search, new LinearLayout.LayoutParams(0, -1, 1));
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    query = s.toString();
                    renderAll(true);
                }
                @Override public void afterTextChanged(Editable value) { }
            });
            toolbar.addView(ui.icon("close", "清空搜索", Color.WHITE, v -> search.setText("")), toolbarButton());
            toolbar.addView(ui.icon("refresh", "刷新", Color.WHITE, v -> refresh(true)), toolbarButton());
            return;
        }
        toolbar.addView(ui.icon("menu", "打开侧栏", Color.WHITE, v -> openDrawer()), toolbarButton());
        TextView title = ui.text("应用管理", 21, Color.WHITE, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        toolbar.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        if (tab != BACKUP) {
            toolbar.addView(ui.icon("sort", "排序", Color.WHITE, this::openSort), toolbarButton());
        }
        toolbar.addView(ui.icon("refresh", "刷新", Color.WHITE, v -> refresh(true)), toolbarButton());
        toolbar.addView(ui.icon("search", "搜索", Color.WHITE, v -> openSearch()), toolbarButton());
    }

    private LinearLayout.LayoutParams toolbarButton() {
        return new LinearLayout.LayoutParams(ui.dp(48), -1);
    }

    private void openSearch() {
        searching = true;
        buildToolbar();
        search.requestFocus();
        search.post(() -> getSystemService(InputMethodManager.class)
                .showSoftInput(search, InputMethodManager.SHOW_IMPLICIT));
    }

    private void hideKeyboard() {
        View focused = getCurrentFocus();
        if (focused != null) getSystemService(InputMethodManager.class)
                .hideSoftInputFromWindow(focused.getWindowToken(), 0);
    }

    private void closeSearch() {
        hideKeyboard();
        searching = false;
        query = "";
        buildToolbar();
        renderAll(true);
    }

    private void selectTab(int next) {
        pager.setCurrentItem(next, AppViews.motionEnabled());
    }

    private void positionIndicator(float position) {
        if (tabTrack.getWidth() == 0) return;
        if (pager != null && pager.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            position = TABS.length - 1 - position;
        }
        float center = (position + 0.5f) * tabTrack.getWidth() / TABS.length;
        tabIndicator.setTranslationX(center - ui.dp(32) / 2f);
    }

    private boolean alphabetical(int page) {
        return page != BACKUP && preferences.getBoolean("alphabetical_" + page, page == SYSTEM);
    }

    private void openSort(View anchor) {
        int page = tab;
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(1, 1, 0, "安装时间（最新在前）").setChecked(!alphabetical(page));
        popup.getMenu().add(1, 2, 1, "应用名称（A–Z）").setChecked(alphabetical(page));
        popup.getMenu().setGroupCheckable(1, true, true);
        popup.setOnMenuItemClickListener(item -> {
            preferences.edit().putBoolean("alphabetical_" + page, item.getItemId() == 2).apply();
            pages[page].render(true);
            return true;
        });
        popup.show();
    }

    private int drawerWidth() {
        return Math.min(ui.dp(320), pageHost.getWidth() - ui.dp(48));
    }

    private void openDrawer() {
        if (drawer != null) return;
        createDrawer();
        moveDrawer(0);
        animateDrawer(true);
    }

    private void createDrawer() {
        pages[tab].hideDateImmediately();
        pages[tab].stopSectionScroll();
        hideLetterBubble();
        drawer = new FrameLayout(this);
        drawerShade = new View(this);
        drawerShade.setBackgroundColor(0x8A102238);
        drawerShade.setContentDescription("关闭侧栏");
        drawerShade.setOnClickListener(v -> closeDrawer());
        drawer.addView(drawerShade, new FrameLayout.LayoutParams(-1, -1));
        HorizontalSwipeFrame gestures = new HorizontalSwipeFrame(this, new HorizontalSwipeFrame.Swipe() {
            @Override public boolean canDrag(float startX, float delta) { return delta < 0 && !closingDrawer; }
            @Override public void drag(float delta) { moveDrawer(1 + Math.min(0, delta) / drawerWidth()); }
            @Override public void finish(float delta, boolean committed) {
                if (committed && delta < 0) closeDrawer();
                else animateDrawer(true);
            }
        });
        drawerPanel = ui.column();
        drawerPanel.setBackgroundColor(Color.WHITE);
        drawerPanel.setClickable(true);
        drawerPanel.setAccessibilityPaneTitle("应用管理侧栏");
        int inset = mainPage.getChildAt(0).getHeight();
        ui.pad(drawerPanel, 16, 12, 16, 14);
        drawerPanel.setPadding(drawerPanel.getPaddingLeft(), inset + ui.dp(12),
                drawerPanel.getPaddingRight(), drawerPanel.getPaddingBottom());

        LinearLayout heading = ui.row();
        TextView title = ui.text("应用管理", 22, AppViews.TEXT, true);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        heading.addView(ui.icon("close", "关闭侧栏", AppViews.MUTED, v -> closeDrawer()),
                new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        drawerPanel.addView(heading);

        ScrollView drawerScroll = new ScrollView(this);
        LinearLayout items = ui.column();
        drawerScroll.addView(items);
        drawerPanel.addView(drawerScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        for (int i = 0; i < TABS.length; i++) {
            int target = i;
            items.addView(drawerItem(i == BACKUP ? "backup" : i == SYSTEM ? "sort" : "open",
                    TABS[i], tab == i, () -> {
                        selectTab(target);
                        closeDrawer();
                    }));
        }
        View divider = new View(this);
        divider.setBackgroundColor(0xFFE0E9F0);
        LinearLayout.LayoutParams line = new LinearLayout.LayoutParams(-1, ui.dp(1));
        line.setMargins(0, ui.dp(12), 0, ui.dp(9));
        items.addView(divider, line);
        items.addView(drawerItem("folder", "备份目录", false, () -> {
            closeDrawer();
            showDirectorySettings();
        }));
        gestures.addView(drawerPanel, new FrameLayout.LayoutParams(-1, -1));
        drawer.addView(gestures, new FrameLayout.LayoutParams(drawerWidth(), -1, Gravity.LEFT));
        pageHost.addView(drawer, new FrameLayout.LayoutParams(-1, -1));
        mainPage.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    private View drawerItem(String icon, String label, boolean selected, Runnable click) {
        LinearLayout container = ui.column();
        LinearLayout item = ui.row();
        ui.pad(item, 11, 0, 12, 0);
        ui.ripple(item, false);
        if (selected) container.setBackground(ui.rounded(AppViews.TINT, 14));
        item.addView(ui.icon(icon, null, AppViews.BLUE, null), new LinearLayout.LayoutParams(ui.dp(30), ui.dp(30)));
        TextView text = ui.text(label, 15, AppViews.TEXT, selected);
        ui.pad(text, 10, 0, 0, 0);
        item.addView(text);
        container.addView(item, new LinearLayout.LayoutParams(-1, ui.dp(50)));
        item.setOnClickListener(v -> click.run());
        item.setFocusable(true);
        item.setSelected(selected);
        return container;
    }

    private void moveDrawer(float fraction) {
        float offset = Math.max(0, Math.min(1, fraction));
        drawerPanel.animate().cancel();
        drawerShade.animate().cancel();
        drawerPanel.setTranslationX((offset - 1) * drawerWidth());
        drawerShade.setAlpha(offset);
    }

    private void animateDrawer(boolean open) {
        if (drawer == null) return;
        closingDrawer = !open;
        drawerPanel.animate().cancel();
        drawerShade.animate().cancel();
        if (!AppViews.motionEnabled()) {
            if (open) moveDrawer(1);
            else removeDrawer();
            return;
        }
        drawerShade.animate().alpha(open ? 1 : 0).setDuration(220).start();
        drawerPanel.animate().translationX(open ? 0 : -drawerWidth()).setDuration(245)
                .setInterpolator(AppViews.EASE_OUT).withLayer()
                .withEndAction(() -> { if (!open) removeDrawer(); }).start();
    }

    private void closeDrawer() {
        if (!closingDrawer) animateDrawer(false);
    }

    private void removeDrawer() {
        pageHost.removeView(drawer);
        drawer = null;
        drawerPanel = null;
        drawerShade = null;
        closingDrawer = false;
        mainPage.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
    }

    private void showDirectorySettings() {
        Uri tree = store.tree();
        new AlertDialog.Builder(this).setTitle("备份目录").setMessage(store.directoryLabel(tree))
                .setPositiveButton("选择目录", (d, w) -> chooseDirectory())
                .setNeutralButton("使用默认目录", (d, w) -> {
                    store.setTree(null);
                    backups = null;
                    refresh(false);
                })
                .setNegativeButton("关闭", null).show();
    }

    private void chooseDirectory() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        if (store.tree() != null) picker.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, store.tree());
        try { startActivityForResult(picker, PICK_DIRECTORY); }
        catch (RuntimeException error) { toast("无法打开文件选择器"); }
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == PICK_DIRECTORY && result == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if ((flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) {
                toast("请选择可写入的目录");
                return;
            }
            try {
                getContentResolver().takePersistableUriPermission(uri, flags);
                store.setTree(uri);
                backups = null;
                refresh(false);
            } catch (RuntimeException error) { toast("无法保存目录访问权限"); }
        } else if (code == ALLOW_INSTALL) {
            BackupStore.Entry entry = pendingInstall;
            pendingInstall = null;
            if (entry != null && getPackageManager().canRequestPackageInstalls()) confirmInstall(entry);
        }
    }

    private void refresh(boolean manual) {
        if (loading) {
            if (!manual) refreshPending = true;
            return;
        }
        int id = ++request;
        boolean backupRequested = tab == BACKUP;
        String heading = (manual ? "刷新" : "读取") + (backupRequested ? "备份列表" : "应用列表");
        loading = true;
        if (catalogProgress != null) {
            catalogProgress.bringToFront();
            catalogProgress.begin(heading, backupRequested ? "正在准备读取备份…" : "正在获取应用清单…");
        }
        showProgress();
        worker.execute(() -> {
            List<AppCatalog.App> result = null;
            BackupStore.Snapshot snapshot = null;
            String error = null;
            try {
                result = AppCatalog.read(getPackageManager(), backupRequested
                        ? ScanProgress.IGNORE : reportScan(id, "个应用"));
            }
            catch (RuntimeException ignored) { }
            try { snapshot = store.scan(backupRequested ? reportScan(id, "个备份") : ScanProgress.IGNORE); }
            catch (IOException | RuntimeException failure) { error = message(failure); }
            List<AppCatalog.App> readApps = result;
            BackupStore.Snapshot readBackups = snapshot;
            String readError = error;
            runOnUiThread(() -> {
                if (isDestroyed() || id != request) return;
                loading = false;
                if (readApps != null) {
                    apps = readApps;
                    installed.clear();
                    appIcons.evictAll();
                    for (AppCatalog.App app : apps) installed.put(app.packageName, app);
                }
                backups = readBackups;
                backupError = readError;
                renderAll(false);
                catalogProgress.finish(backupRequested ? readError == null : readApps != null);
                if (manual || readApps == null) {
                    if (backupRequested && backupError != null) toast("无法刷新备份：" + backupError);
                    else if (readApps == null) toast("无法读取已安装应用");
                    else toast("已刷新");
                }
                if (refreshPending) {
                    refreshPending = false;
                    refresh(false);
                }
            });
        });
    }

    private void updatePackage(String packageName) {
        worker.execute(() -> {
            AppCatalog.App app;
            try {
                app = AppCatalog.readPackage(getPackageManager(), packageName);
            } catch (RuntimeException failure) {
                return;
            }
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (hasWindowFocus()) applyPackageUpdate(packageName, app);
                else pendingPackageUpdates.put(packageName, app);
            });
        });
    }

    private void applyPackageUpdate(String packageName, AppCatalog.App replacement) {
        AppCatalog.App previous = installed.get(packageName);
        if (previous == null && replacement == null) return;
        String key = "app:" + packageName;
        int oldPage = previous == null ? -1 : previous.system ? SYSTEM : USER;
        int newPage = replacement == null ? -1 : replacement.system ? SYSTEM : USER;
        if (replacement == null) {
            installed.remove(packageName);
            if (key.equals(detailKey)) showMain();
        } else {
            installed.put(packageName, replacement);
        }
        apps.removeIf(app -> app.packageName.equals(packageName));
        if (replacement != null) apps.add(replacement);
        appIcons.remove(packageName);
        Set<String> changed = Collections.singleton(key);
        if (oldPage >= 0) pages[oldPage].render(false, changed);
        if (newPage >= 0 && newPage != oldPage) pages[newPage].render(false, changed);

        if (backups != null) {
            Set<String> regrouped = new HashSet<>();
            for (BackupStore.Entry entry : backups.entries) {
                if (entry.metadata != null && packageName.equals(entry.metadata.packageName)
                        && AppCatalog.BackupStatus.compare(entry, previous)
                        != AppCatalog.BackupStatus.compare(entry, replacement)) {
                    regrouped.add("backup:" + entry.key());
                }
            }
            if (!regrouped.isEmpty()) pages[BACKUP].render(false, regrouped);
        }
        updateTabCounts();
    }

    private ScanProgress reportScan(int id, String unit) {
        return new ScanProgress() {
            private long lastReport;

            @Override public void update(int read, int total, String name) {
                long now = SystemClock.uptimeMillis();
                if (read != 0 && read != total && now - lastReport < 85) return;
                lastReport = now;
                runOnUiThread(() -> {
                    if (!isDestroyed() && id == request) catalogProgress.report(read, total, name, unit);
                });
            }
        };
    }

    private void showProgress() {
        if (mainProgress != null) mainProgress.setVisibility(operating && !loading ? View.VISIBLE : View.INVISIBLE);
        if (progress != null && progress != mainProgress) {
            progress.setVisibility(loading || operating ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private void updateTabSelection() {
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setTextColor(i == tab ? Color.WHITE : 0xFFB8D0E2);
            tabs[i].setSelected(i == tab);
        }
    }

    private void renderAll(boolean resetScroll) {
        updateTabCounts();
        for (ListPage page : pages) page.render(resetScroll);
        updateTabSelection();
        showProgress();
    }

    private void updateTabCounts() {
        int systems = 0;
        for (AppCatalog.App app : apps) if (app.system) systems++;
        int[] counts = {apps.size() - systems, systems, backups == null ? 0 : backups.entries.size()};
        for (int i = 0; i < tabs.length; i++) {
            tabs[i].setText(TABS[i] + " (" + counts[i] + ")");
        }
    }

    private boolean buildAppRows(ListPage page) {
        List<Row> rows = page.rows;
        List<AppCatalog.App> filtered = new ArrayList<>();
        for (AppCatalog.App app : apps) {
            if (app.system == (page.position == SYSTEM) && AppCatalog.matches(query, app.name, app.packageName)) {
                filtered.add(app);
            }
        }
        boolean alphabet = alphabetical(page.position);
        filtered.sort(alphabet ? catalog.byName() : Comparator
                .comparingLong((AppCatalog.App app) -> app.installedAt).reversed()
                .thenComparing(catalog.byName()));
        String previous = "";
        for (AppCatalog.App app : filtered) {
            if (alphabet) {
                String letter = catalog.section(app.name);
                if (!previous.equals(letter)) {
                    page.sections.put(letter.charAt(0), rows.size());
                    rows.add(Row.section(letter, 0));
                    previous = letter;
                }
            }
            rows.add(Row.app(app));
        }
        return !filtered.isEmpty();
    }

    private boolean buildBackupRows(List<Row> rows) {
        if (backups == null) return false;
        rows.add(Row.capacity());
        boolean any = false;
        for (AppCatalog.BackupStatus status : AppCatalog.BackupStatus.values()) {
            boolean sectionAdded = false;
            for (BackupStore.Entry entry : backups.entries) {
                String pkg = entry.metadata == null ? "" : entry.metadata.packageName;
                if (AppCatalog.BackupStatus.compare(entry, installed.get(pkg)) != status
                        || !AppCatalog.matches(query, entry.label(), pkg, entry.name)) continue;
                if (!sectionAdded) {
                    rows.add(Row.section(status.label, status.color));
                    sectionAdded = true;
                }
                rows.add(Row.backup(entry, status));
                any = true;
            }
        }
        if (!any) rows.add(Row.note(query.isEmpty() ? "暂无备份\n展开应用卡片，点击「备份」保存安装包"
                : "没有匹配的备份"));
        return any;
    }

    private void buildIndex(ListPage page) {
        AlphabetRail index = page.index;
        index.setVisibility(page.sections.isEmpty() ? View.GONE : View.VISIBLE);
        index.updateSections();
    }

    private final class AlphabetRail extends LinearLayout {
        private static final String LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ#";
        private final ListPage page;
        private TextView highlighted;
        private int pointer = MotionEvent.INVALID_POINTER_ID;
        private int lastLetter = -1;

        AlphabetRail(ListPage page) {
            super(MainActivity.this);
            this.page = page;
            setOrientation(VERTICAL);
            ui.pad(this, 0, 3, 0, 3);
            for (int i = 0; i < LETTERS.length(); i++) {
                TextView label = ui.text(LETTERS.substring(i, i + 1), 11, AppViews.DARK_BLUE, true);
                label.setGravity(Gravity.CENTER);
                addView(label, new LinearLayout.LayoutParams(-1, 0, 1));
            }
        }

        void updateSections() {
            stopTracking();
            for (int i = 0; i < LETTERS.length(); i++) {
                char letter = LETTERS.charAt(i);
                TextView label = (TextView) getChildAt(i);
                boolean exists = page.sections.containsKey(letter);
                label.setTextColor(exists ? AppViews.DARK_BLUE : 0xFF9BA7AF);
                label.setContentDescription(exists ? "跳转到 " + letter + " 组" : null);
                label.setOnClickListener(exists ? view -> {
                    if (jump(letter)) scheduleLetterFade();
                } : null);
                label.setClickable(exists);
                label.setFocusable(exists);
                label.setImportantForAccessibility(exists ? IMPORTANT_FOR_ACCESSIBILITY_YES
                        : IMPORTANT_FOR_ACCESSIBILITY_NO);
            }
        }

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (!canOpenPage(page)) return false;
                    pointer = event.getPointerId(0);
                    getParent().requestDisallowInterceptTouchEvent(true);
                    track(event.getY());
                    return true;
                case MotionEvent.ACTION_MOVE:
                    int index = event.findPointerIndex(pointer);
                    if (index >= 0) track(event.getY(index));
                    return true;
                case MotionEvent.ACTION_UP:
                    if (pointer != MotionEvent.INVALID_POINTER_ID) track(event.getY());
                    stopTracking();
                    scheduleLetterFade();
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                    if (event.getPointerId(event.getActionIndex()) == pointer) {
                        stopTracking();
                        scheduleLetterFade();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    stopTracking();
                    scheduleLetterFade();
                    return true;
                default:
                    return pointer != MotionEvent.INVALID_POINTER_ID;
            }
        }

        private void track(float y) {
            int letter = 0;
            while (letter < LETTERS.length() - 1 && y >= getChildAt(letter).getBottom()) letter++;
            if (letter == lastLetter) return;
            lastLetter = letter;
            if (highlighted != null) highlighted.setBackground(null);
            highlighted = (TextView) getChildAt(letter);
            if (jump(LETTERS.charAt(letter))) highlighted.setBackground(ui.rounded(AppViews.TINT, 10));
        }

        private boolean jump(char letter) {
            Integer row = page.sections.get(letter);
            if (row == null || !canOpenPage(page)) return false;
            showLetterBubble(letter);
            page.scrollToSection(row);
            return true;
        }

        private void stopTracking() {
            if (pointer != MotionEvent.INVALID_POINTER_ID && getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(false);
            }
            pointer = MotionEvent.INVALID_POINTER_ID;
            lastLetter = -1;
            if (highlighted != null) {
                highlighted.setBackground(null);
                highlighted = null;
            }
        }
    }

    private void showLetterBubble(char letter) {
        letterBubble.removeCallbacks(fadeLetter);
        letterBubble.animate().cancel();
        letterBubble.setText(String.valueOf(letter));
        letterBubble.setAlpha(1);
        letterBubble.setVisibility(View.VISIBLE);
    }

    private void scheduleLetterFade() {
        if (letterBubble.getVisibility() != View.VISIBLE) return;
        letterBubble.removeCallbacks(fadeLetter);
        letterBubble.postDelayed(fadeLetter, 500);
    }

    private void fadeLetterBubble() {
        if (AppViews.motionEnabled()) {
            letterBubble.animate().alpha(0).setDuration(160).setInterpolator(AppViews.EASE_OUT)
                    .withEndAction(() -> letterBubble.setVisibility(View.GONE)).start();
        } else {
            letterBubble.setVisibility(View.GONE);
        }
    }

    private void hideLetterBubble() {
        letterBubble.removeCallbacks(fadeLetter);
        letterBubble.animate().cancel();
        letterBubble.setVisibility(View.GONE);
    }

    private final class ListPage extends FrameLayout {
        final int position;
        final List<Row> rows = new ArrayList<>();
        final Map<Character, Integer> sections = new HashMap<>();
        final ListView list;
        final AlphabetRail index;
        final TextView empty;
        final TextView dateBubble;
        final ListAdapter adapter;
        String expanded;
        private boolean verticalScroll;
        private boolean timeOrder;
        private int dateRow = -1;
        private final Runnable hideDate = this::fadeDate;
        private int sectionTarget = -1;
        private boolean sectionScrolling;
        private final Runnable sectionScroll = this::runSectionScroll;
        private OneShotPreDrawListener sectionLayout;

        ListPage(int position) {
            super(MainActivity.this);
            this.position = position;
            setBackgroundColor(AppViews.SURFACE);
            LinearLayout content = ui.row();
            content.setGravity(Gravity.TOP);
            list = new ListView(MainActivity.this);
            list.setDivider(null);
            list.setClipToPadding(false);
            list.setPadding(0, ui.dp(8), 0, ui.dp(10));
            list.setSelector(android.R.color.transparent);
            list.setItemsCanFocus(true);
            adapter = new ListAdapter(this);
            list.setAdapter(adapter);
            content.addView(list, new LinearLayout.LayoutParams(0, -1, 1));
            index = new AlphabetRail(this);
            content.addView(index, new LinearLayout.LayoutParams(ui.dp(28), -1));
            addView(content, new FrameLayout.LayoutParams(-1, -1));
            empty = ui.text("", 15, AppViews.MUTED, false);
            empty.setGravity(Gravity.CENTER);
            ui.pad(empty, 24, 20, 24, 20);
            addView(empty, new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER));

            dateBubble = ui.text("", 13, Color.WHITE, true);
            dateBubble.setBackground(ui.rounded(AppViews.DARK_BLUE, 16));
            ui.pad(dateBubble, 12, 7, 12, 7);
            dateBubble.setVisibility(GONE);
            dateBubble.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams badge = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            badge.topMargin = ui.dp(12);
            addView(dateBubble, badge);
            list.setOnScrollListener(new AbsListView.OnScrollListener() {
                @Override public void onScrollStateChanged(AbsListView view, int state) {
                    if (state == SCROLL_STATE_TOUCH_SCROLL) cancelSectionRequest();
                    if (state != SCROLL_STATE_FLING) sectionScrolling = false;
                    verticalScroll = state != SCROLL_STATE_IDLE;
                    if (verticalScroll) showDate(list.getFirstVisiblePosition());
                    else if (dateBubble.getVisibility() == VISIBLE) postDelayed(hideDate, 700);
                }

                @Override public void onScroll(AbsListView view, int first, int visible, int total) {
                    if (verticalScroll && visible > 0) showDate(first);
                }
            });
        }

        void render(boolean resetScroll) {
            render(resetScroll, Collections.emptySet());
        }

        void render(boolean resetScroll, Set<String> changedKeys) {
            hideDateImmediately();
            stopSectionScroll();
            if (position == tab) hideLetterBubble();
            timeOrder = position != BACKUP && !alphabetical(position);
            View top = list.getChildAt(0);
            int first = top == null ? 0 : list.getFirstVisiblePosition();
            int offset = top == null ? 0 : top.getTop() - list.getPaddingTop();
            String anchor = null;
            if (!changedKeys.isEmpty()) {
                boolean animate = canOpenPage(this) && AppViews.motionEnabled();
                for (int i = 0; i < list.getChildCount() && first + i < rows.size(); i++) {
                    String key = rows.get(first + i).key();
                    View child = list.getChildAt(i);
                    if (changedKeys.contains(key)) {
                        if (animate && child instanceof PackageCard) slideOut(child);
                    } else if (anchor == null) {
                        anchor = key;
                        offset = child.getTop() - list.getPaddingTop();
                    }
                }
            }
            if (resetScroll) expanded = null;
            rows.clear();
            sections.clear();
            boolean any = position == BACKUP ? buildBackupRows(rows) : buildAppRows(this);
            buildIndex(this);
            if (expanded != null && findRow(expanded) < 0) expanded = null;
            adapter.notifyDataSetChanged();
            if (!rows.isEmpty()) {
                int anchorPosition = anchor == null ? -1 : findRow(anchor);
                list.setSelectionFromTop(resetScroll ? 0 : anchorPosition >= 0
                                ? anchorPosition : Math.min(first, rows.size() - 1),
                        resetScroll ? 0 : offset);
            }
            if (!changedKeys.isEmpty() && canOpenPage(this) && AppViews.motionEnabled()) {
                OneShotPreDrawListener.add(list, () -> slideIn(changedKeys));
            }
            boolean overlay = !any && (position != BACKUP || backups == null);
            empty.setVisibility(overlay ? View.VISIBLE : View.GONE);
            if (overlay) {
                if (loading) empty.setText("正在读取…");
                else if (position == BACKUP && backupError != null) {
                    empty.setText("无法读取备份目录\n" + backupError);
                } else {
                    empty.setText(query.isEmpty() ? (position == BACKUP ? "暂无备份" : "暂无应用")
                            : "没有匹配的结果");
                }
            }
        }

        private int findRow(String key) {
            for (int i = 0; i < rows.size(); i++) {
                if (key.equals(rows.get(i).key())) return i;
            }
            return -1;
        }

        private void slideOut(View card) {
            if (card.getWidth() <= 0 || card.getHeight() <= 0) return;
            Bitmap snapshot = Bitmap.createBitmap(card.getWidth(), card.getHeight(), Bitmap.Config.ARGB_8888);
            try {
                card.draw(new Canvas(snapshot));
            } catch (RuntimeException unsupportedDrawable) {
                snapshot.recycle();
                return;
            }
            ImageView ghost = new ImageView(MainActivity.this);
            ghost.setImageBitmap(snapshot);
            ghost.layout(card.getLeft(), card.getTop(), card.getRight(), card.getBottom());
            ghost.setTranslationX(card.getTranslationX());
            ghost.setAlpha(card.getAlpha());
            list.getOverlay().add(ghost);
            ghost.animate().translationX(-list.getWidth()).alpha(0).setDuration(220)
                    .setInterpolator(AppViews.EASE_OUT)
                    .withEndAction(() -> list.getOverlay().remove(ghost)).start();
        }

        private void slideIn(Set<String> changedKeys) {
            if (!canOpenPage(this)) return;
            for (int i = 0; i < list.getChildCount(); i++) {
                if (!(list.getChildAt(i) instanceof PackageCard)) continue;
                PackageCard card = (PackageCard) list.getChildAt(i);
                if (changedKeys.contains(card.key)) ui.enter(card, list.getWidth(), 0.55f, 260);
            }
        }

        void scrollToSection(int row) {
            cancelSectionRequest();
            sectionTarget = row;
            list.postOnAnimation(sectionScroll);
        }

        private void runSectionScroll() {
            int row = sectionTarget;
            sectionTarget = -1;
            if (row < 0 || row >= rows.size() || !canOpenPage(this)) return;
            list.smoothScrollBy(0, 0);
            int visible = list.getChildCount();
            if (!AppViews.motionEnabled() || visible == 0) {
                list.setSelectionFromTop(row, 0);
                return;
            }
            int first = list.getFirstVisiblePosition();
            int distance = row - first;
            // Keep a distant jump responsive by animating only its final part.
            if (Math.abs(distance) > visible) {
                int approach = Math.max(0, row - Integer.signum(distance) * Math.max(1, visible / 2));
                list.setSelectionFromTop(Math.min(approach, rows.size() - 1), 0);
                sectionLayout = OneShotPreDrawListener.add(list, () -> {
                    sectionLayout = null;
                    if (canOpenPage(this)) animateToSection(row);
                });
            } else {
                animateToSection(row);
            }
        }

        private void animateToSection(int row) {
            sectionScrolling = true;
            list.smoothScrollToPositionFromTop(row, 0, 190);
        }

        private void cancelSectionRequest() {
            list.removeCallbacks(sectionScroll);
            sectionTarget = -1;
            if (sectionLayout != null) {
                sectionLayout.removeListener();
                sectionLayout = null;
            }
        }

        private void stopSectionScroll() {
            boolean navigating = sectionTarget >= 0 || sectionLayout != null || sectionScrolling;
            cancelSectionRequest();
            if (navigating) list.smoothScrollBy(0, 0);
            sectionScrolling = false;
        }

        private void showDate(int first) {
            if (!timeOrder || !canOpenPage(this) || first >= rows.size()) return;
            AppCatalog.App app = rows.get(first).app;
            if (app == null) return;
            removeCallbacks(hideDate);
            if (dateRow != first) {
                dateBubble.setText(app.installedAt > 0 ? "安装日期  " + date(app.installedAt, false)
                        : "安装日期未知");
                dateRow = first;
            }
            if (dateBubble.getVisibility() == VISIBLE && dateBubble.getAlpha() == 1) return;
            dateBubble.animate().cancel();
            dateBubble.setVisibility(VISIBLE);
            dateBubble.setAlpha(1);
        }

        private void fadeDate() {
            if (AppViews.motionEnabled()) {
                dateBubble.animate().alpha(0).setDuration(150)
                        .withEndAction(() -> dateBubble.setVisibility(GONE)).start();
            } else {
                dateBubble.setVisibility(GONE);
            }
        }

        private void hideDateImmediately() {
            removeCallbacks(hideDate);
            dateBubble.animate().cancel();
            dateBubble.setVisibility(GONE);
            dateRow = -1;
        }

        @Override protected void onDetachedFromWindow() {
            hideDateImmediately();
            stopSectionScroll();
            super.onDetachedFromWindow();
        }

        void toggle(PackageCard target) {
            if (!canOpenPage(this)) return;
            boolean open = !target.key.equals(expanded);
            String previous = expanded;
            expanded = open ? target.key : null;
            for (int i = 0; i < list.getChildCount(); i++) {
                View child = list.getChildAt(i);
                if (child instanceof PackageCard && child != target) {
                    PackageCard card = (PackageCard) child;
                    if (card.key.equals(previous)) card.setExpanded(false, true);
                }
            }
            target.setExpanded(open, true);
            if (open && !AppViews.motionEnabled()) target.post(() -> reveal(target));
        }

        void reveal(PackageCard card) {
            if (!canOpenPage(this) || !card.key.equals(expanded) || card.getParent() != list) return;
            int overflow = card.getBottom() - (list.getHeight() - list.getPaddingBottom());
            if (overflow > 0) list.smoothScrollBy(overflow, 190);
        }
    }

    private boolean canOpenPage(ListPage page) {
        return !detail && drawer == null && tab == page.position
                && pager.getScrollState() == ViewPager2.SCROLL_STATE_IDLE;
    }

    private final class PagerAdapter extends RecyclerView.Adapter<PageHolder> {
        @Override public int getItemCount() { return pages.length; }

        @Override public PageHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            FrameLayout container = new FrameLayout(parent.getContext());
            container.setLayoutParams(new RecyclerView.LayoutParams(-1, -1));
            return new PageHolder(container);
        }

        @Override public void onBindViewHolder(PageHolder holder, int position) {
            ListPage page = pages[position];
            FrameLayout container = (FrameLayout) holder.itemView;
            if (page.getParent() == container) return;
            if (page.getParent() instanceof ViewGroup) ((ViewGroup) page.getParent()).removeView(page);
            container.removeAllViews();
            container.addView(page, new FrameLayout.LayoutParams(-1, -1));
        }
    }

    private static final class PageHolder extends RecyclerView.ViewHolder {
        PageHolder(View page) { super(page); }
    }

    private static final class Row {
        final AppCatalog.App app;
        final BackupStore.Entry backup;
        final AppCatalog.BackupStatus status;
        final String label;
        final int color;
        final boolean note;

        private Row(AppCatalog.App app, BackupStore.Entry backup, AppCatalog.BackupStatus status,
                    String label, int color, boolean note) {
            this.app = app;
            this.backup = backup;
            this.status = status;
            this.label = label;
            this.color = color;
            this.note = note;
        }

        static Row app(AppCatalog.App app) { return new Row(app, null, null, null, 0, false); }
        static Row backup(BackupStore.Entry entry, AppCatalog.BackupStatus status) {
            return new Row(null, entry, status, null, 0, false);
        }
        static Row section(String label, int color) { return new Row(null, null, null, label, color, false); }
        static Row note(String label) { return new Row(null, null, null, label, 0, true); }
        static Row capacity() { return new Row(null, null, null, null, 0, false); }

        String key() {
            if (app != null) return "app:" + app.packageName;
            if (backup != null) return "backup:" + backup.key();
            if (label == null) return "capacity";
            return (note ? "note:" : "section:") + label;
        }
    }

    private final class ListAdapter extends BaseAdapter {
        private final ListPage page;

        ListAdapter(ListPage page) { this.page = page; }

        @Override public int getCount() { return page.rows.size(); }
        @Override public Row getItem(int position) { return page.rows.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public int getViewTypeCount() { return 3; }
        @Override public int getItemViewType(int position) {
            Row row = getItem(position);
            if (row.app != null || row.backup != null) return 0;
            return row.label == null ? 2 : 1;
        }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            Row row = getItem(position);
            if (row.app != null) {
                AppCatalog.App app = row.app;
                PackageCard card = recycled instanceof PackageCard ? (PackageCard) recycled : new PackageCard(page);
                card.bind(row.key(), app.name, app.packageName,
                        "v" + app.version + "  |  " + size(app.bytes) + "  |  " + date(app.installedAt, false),
                        appIcon(app), app.system ? 0xFFB64C66 : 0xFF1683B2, "", 0,
                        () -> showApp(app),
                        () -> appActions(app, false));
                return card;
            }
            if (row.backup != null) {
                BackupStore.Entry entry = row.backup;
                Drawable icon = entry.metadata != null ? entry.metadata.newIcon() : getPackageManager().getDefaultActivityIcon();
                PackageCard card = recycled instanceof PackageCard ? (PackageCard) recycled : new PackageCard(page);
                card.bind(row.key(), entry.label(), entry.name,
                        (entry.metadata == null ? "" : "v" + entry.metadata.version + "  |  ")
                                + size(entry.bytes) + "  |  " + date(entry.modified, false),
                        icon, 0xFFE69218, row.status.badge, row.status.color,
                        () -> showBackup(entry),
                        () -> backupActions(entry, false));
                return card;
            }
            if (row.label == null) return capacity();
            FrameLayout wrap = new FrameLayout(MainActivity.this);
            if (row.note) {
                TextView note = ui.text(row.label, 14, AppViews.MUTED, false);
                note.setGravity(Gravity.CENTER);
                ui.pad(note, 12, 32, 12, 20);
                wrap.addView(note, new FrameLayout.LayoutParams(-1, -2));
            } else {
                TextView label = ui.text(row.label, row.color == 0 ? 13 : 14,
                        row.color == 0 ? AppViews.MUTED : Color.WHITE, true);
                ui.pad(label, 14, 5, 14, 5);
                if (row.color != 0) label.setBackground(ui.rounded(row.color, 20));
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-2, -2,
                        row.color == 0 ? Gravity.START : Gravity.CENTER);
                params.setMargins(ui.dp(8), ui.dp(5), ui.dp(8), ui.dp(3));
                wrap.addView(label, params);
            }
            return wrap;
        }
    }

    private Drawable appIcon(AppCatalog.App app) {
        Drawable.ConstantState cached = appIcons.get(app.packageName);
        if (cached != null) return cached.newDrawable().mutate();
        try {
            Drawable icon = getPackageManager().getApplicationIcon(app.packageName);
            Drawable.ConstantState state = icon.getConstantState();
            if (state != null) appIcons.put(app.packageName, state);
            return icon;
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            return getPackageManager().getDefaultActivityIcon();
        }
    }

    private FrameLayout cardFrame(LinearLayout card) {
        FrameLayout wrap = new FrameLayout(this);
        styleCard(wrap, card);
        return wrap;
    }

    private void styleCard(FrameLayout wrap, LinearLayout card) {
        card.setBackground(ui.rounded(Color.WHITE, 19));
        card.setElevation(ui.dp(1));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2);
        params.setMargins(ui.dp(13), ui.dp(5), ui.dp(13), ui.dp(5));
        wrap.addView(card, params);
        wrap.setClipChildren(false);
    }

    private final class PackageCard extends FrameLayout {
        private final ListPage page;
        private final LinearLayout summary;
        private final FrameLayout tile;
        private final ImageView image;
        private final TextView mark;
        private final TextView name;
        private final TextView subtitle;
        private final TextView metadata;
        private final View chevron;
        private final FrameLayout actionArea;
        private String key;
        private Runnable details;
        private Supplier<View> actions;
        private ValueAnimator expansion;
        private float expansionFraction;

        PackageCard(ListPage page) {
            super(MainActivity.this);
            this.page = page;
            LinearLayout card = ui.column();
            LinearLayout header = ui.row();
            summary = ui.row();
            ui.pad(summary, 11, 10, 3, 10);
            ui.ripple(summary, false);
            summary.setMinimumHeight(ui.dp(79));
            summary.setFocusable(true);
            summary.setOnClickListener(v -> { if (canOpenPage(page)) details.run(); });

            tile = new FrameLayout(MainActivity.this);
            image = new ImageView(MainActivity.this);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            tile.addView(image, new FrameLayout.LayoutParams(ui.dp(42), ui.dp(42), Gravity.CENTER));
            mark = ui.text("", 12, Color.WHITE, true);
            mark.setGravity(Gravity.CENTER);
            tile.addView(mark, new FrameLayout.LayoutParams(ui.dp(19), ui.dp(19), Gravity.END | Gravity.BOTTOM));
            summary.addView(tile, new LinearLayout.LayoutParams(ui.dp(52), ui.dp(52)));

            LinearLayout labels = ui.column();
            ui.pad(labels, 11, 0, 0, 0);
            name = ellipsized("", 17, AppViews.TEXT, true);
            subtitle = ellipsized("", 12, AppViews.MUTED, false);
            metadata = ellipsized("", 12, AppViews.MUTED, false);
            labels.addView(name);
            labels.addView(subtitle);
            labels.addView(metadata);
            summary.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
            header.addView(summary, new LinearLayout.LayoutParams(0, -2, 1));
            chevron = ui.icon("down", "展开操作", AppViews.BLUE, v -> page.toggle(this));
            header.addView(chevron, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
            card.addView(header);

            actionArea = new FrameLayout(MainActivity.this);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.setMargins(ui.dp(8), 0, ui.dp(8), 0);
            card.addView(actionArea, params);
            styleCard(this, card);
        }

        void bind(String key, String title, String sub, String meta, Drawable icon, int tint,
                  String badge, int badgeColor, Runnable details, Supplier<View> actions) {
            if (!key.equals(this.key)) {
                animate().cancel();
                setTranslationX(0);
                setAlpha(1);
            }
            this.key = key;
            this.details = details;
            this.actions = actions;
            name.setText(title);
            subtitle.setText(sub);
            metadata.setText(meta);
            tile.setBackground(ui.outline(tint));
            image.setImageDrawable(icon);
            mark.setText(badge);
            mark.setVisibility(badge.isEmpty() ? View.GONE : View.VISIBLE);
            if (!badge.isEmpty()) mark.setBackground(ui.rounded(badgeColor, 10));
            summary.setContentDescription(title + "，" + sub + "，" + meta + "，查看详情");
            actionArea.removeAllViews();
            setExpanded(key.equals(page.expanded), false);
        }

        void setExpanded(boolean open, boolean animated) {
            if (expansion != null) {
                expansion.removeAllListeners();
                expansion.cancel();
                expansion = null;
            }
            chevron.setContentDescription(open ? "收起操作" : "展开操作");
            chevron.setSelected(open);
            if (open && actionArea.getChildCount() == 0) {
                actionArea.addView(actions.get(), new FrameLayout.LayoutParams(-1, -2));
            }
            View buttons = actionArea.getChildAt(0);
            float end = open ? 1 : 0;
            if (!animated || !AppViews.motionEnabled() || getWidth() == 0 || buttons == null
                    || expansionFraction == end) {
                finishExpansion(open);
                return;
            }
            int width = actionArea.getWidth();
            if (width <= 0) {
                LinearLayout.LayoutParams actionParams = (LinearLayout.LayoutParams) actionArea.getLayoutParams();
                FrameLayout.LayoutParams cardParams = (FrameLayout.LayoutParams) ((View) actionArea.getParent()).getLayoutParams();
                width = getWidth() - cardParams.leftMargin - cardParams.rightMargin
                        - actionParams.leftMargin - actionParams.rightMargin;
            }
            if (width <= 0) {
                finishExpansion(open);
                return;
            }
            int fullHeight = buttons.getMeasuredHeight();
            if (fullHeight == 0) {
                buttons.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                fullHeight = buttons.getMeasuredHeight();
            }
            if (fullHeight <= 0) {
                finishExpansion(open);
                return;
            }
            int buttonHeight = fullHeight;
            actionArea.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            buttons.getLayoutParams().height = buttonHeight;
            updateExpansion(expansionFraction, buttons, buttonHeight);
            actionArea.setVisibility(VISIBLE);
            expansion = ValueAnimator.ofFloat(expansionFraction, end);
            expansion.setInterpolator(open ? AppViews.EASE_OUT : COLLAPSE_EASING);
            expansion.setDuration(Math.max(90, Math.round(215 * Math.abs(end - expansionFraction))));
            expansion.addUpdateListener(animation ->
                    updateExpansion((float) animation.getAnimatedValue(), buttons, buttonHeight));
            expansion.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(android.animation.Animator animation) {
                    expansion = null;
                    finishExpansion(open);
                    if (open) page.reveal(PackageCard.this);
                }
            });
            expansion.start();
        }

        private void updateExpansion(float fraction, View buttons, int fullHeight) {
            expansionFraction = fraction;
            chevron.setRotation(180 * fraction);
            buttons.setAlpha(fraction);
            updateActionLayout(Math.round(fullHeight * fraction), Math.round(ui.dp(8) * fraction));
        }

        private void updateActionLayout(int height, int bottomMargin) {
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) actionArea.getLayoutParams();
            if (params.height != height || params.bottomMargin != bottomMargin) {
                params.height = height;
                params.bottomMargin = bottomMargin;
                actionArea.setLayoutParams(params);
            }
        }

        private void finishExpansion(boolean open) {
            expansionFraction = open ? 1 : 0;
            chevron.setRotation(180 * expansionFraction);
            actionArea.setVisibility(open ? VISIBLE : GONE);
            actionArea.setImportantForAccessibility(open ? IMPORTANT_FOR_ACCESSIBILITY_AUTO
                    : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            updateActionLayout(LayoutParams.WRAP_CONTENT, open ? ui.dp(8) : 0);
            if (open) {
                View buttons = actionArea.getChildAt(0);
                buttons.getLayoutParams().height = LayoutParams.WRAP_CONTENT;
                buttons.setAlpha(1);
            } else {
                actionArea.removeAllViews();
            }
        }

        @Override protected void onDetachedFromWindow() {
            animate().cancel();
            setTranslationX(0);
            setAlpha(1);
            if (expansion != null) {
                expansion.removeAllListeners();
                expansion.cancel();
                expansion = null;
                finishExpansion(key.equals(page.expanded));
            }
            super.onDetachedFromWindow();
        }
    }

    private TextView ellipsized(String text, int size, int color, boolean bold) {
        TextView label = ui.text(text, size, color, bold);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        return label;
    }

    private View capacity() {
        LinearLayout card = ui.column();
        ui.pad(card, 10, 10, 10, 10);
        LinearLayout cells = ui.row();
        cells.addView(metric("APK 备份目录", size(backups.backupBytes), 0xFF9A5405), new LinearLayout.LayoutParams(0, -2, 1));
        cells.addView(metric("存储已用", backups.totalBytes > 0 ? size(backups.usedBytes) : "—", 0xFFC82D3C),
                new LinearLayout.LayoutParams(0, -2, 1));
        cells.addView(metric("存储总量", backups.totalBytes > 0 ? size(backups.totalBytes) : "—", AppViews.TEXT),
                new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(cells);
        if (backups.totalBytes > 0) {
            ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1000);
            bar.setProgress((int) Math.min(1000, 1000.0 * backups.usedBytes / backups.totalBytes));
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFFE84B55));
            bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFFE1E8EE));
            bar.setContentDescription("存储空间使用比例");
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, ui.dp(8));
            params.topMargin = ui.dp(8);
            card.addView(bar, params);
        }
        return cardFrame(card);
    }

    private LinearLayout metric(String label, String value, int color) {
        LinearLayout metric = ui.column();
        TextView title = ui.text(label, 12, color, true);
        title.setGravity(Gravity.CENTER);
        TextView number = ui.text(value, 15, color, true);
        number.setGravity(Gravity.CENTER);
        metric.addView(title);
        metric.addView(number);
        return metric;
    }

    private LinearLayout actionBar() {
        LinearLayout actions = ui.row();
        actions.setBaselineAligned(false);
        actions.setBackground(ui.rounded(0xFFE8F2F8, 14));
        ui.pad(actions, 2, 3, 2, 3);
        return actions;
    }

    private void action(LinearLayout bar, String icon, String label, Runnable perform) {
        LinearLayout button = ui.column();
        button.setGravity(Gravity.CENTER);
        button.setMinimumHeight(ui.dp(56));
        int ink = "delete".equals(icon) ? 0xFFAF3450 : AppViews.BLUE;
        button.addView(ui.icon(icon, null, ink, null), new LinearLayout.LayoutParams(-1, ui.dp(26)));
        TextView text = ui.text(label, 11, "delete".equals(icon) ? ink : AppViews.TEXT, true);
        text.setGravity(Gravity.CENTER);
        button.addView(text, new LinearLayout.LayoutParams(-1, -2));
        ui.ripple(button, false);
        button.setOnClickListener(v -> perform.run());
        button.setFocusable(true);
        button.setContentDescription(label);
        bar.addView(button, new LinearLayout.LayoutParams(0, -1, 1));
    }

    private View appActions(AppCatalog.App app, boolean inDetail) {
        LinearLayout bar = actionBar();
        if (app.launch != null || !app.system) action(bar, "open", "打开", () -> launch(app));
        if (!inDetail) action(bar, "info", "信息", () -> showApp(app));
        action(bar, "backup", "备份", () -> perform(() -> store.save(app), saved -> {
            if (java.util.Objects.equals(saved.directory, store.tree())) {
                if (backups == null) backups = new BackupStore.Snapshot(Collections.emptyList(),
                        0, 0, 0, store.directoryLabel(saved.directory));
                backups = backups.with(saved.entry);
                pages[BACKUP].render(false, Collections.singleton("backup:" + saved.entry.key()));
                updateTabCounts();
            }
            toast("已备份：" + saved.entry.name);
        }));
        if (!app.system) action(bar, "delete", "卸载", () -> uninstall(app));
        action(bar, "market", "应用市场", () -> market(app.packageName));
        action(bar, "share", "分享", () -> perform(() -> store.forSharing(app), this::share));
        return bar;
    }

    private View backupActions(BackupStore.Entry entry, boolean inDetail) {
        LinearLayout bar = actionBar();
        action(bar, "install", "安装", () -> confirmInstall(entry));
        action(bar, "delete", "删除", () -> confirmDelete(entry));
        action(bar, "market", "市场", () -> {
            if (entry.metadata != null) market(entry.metadata.packageName);
            else toast("无法识别应用包名");
        });
        action(bar, "share", "分享", () -> perform(() -> store.forSharing(entry), this::share));
        if (!inDetail) action(bar, "info", "详情", () -> showBackup(entry));
        return bar;
    }

    private LinearLayout detailPage(String title, String subtitle, Drawable icon, View actions) {
        hideKeyboard();
        if (!detail) {
            pages[tab].hideDateImmediately();
            pages[tab].stopSectionScroll();
            hideLetterBubble();
            mainPage.animate().cancel();
            mainPage.setTranslationX(0);
            mainPage.setAlpha(1);
            mainPage.setVisibility(View.INVISIBLE);
            mainPage.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        }
        detail = true;
        root = page(AppViews.SURFACE);
        LinearLayout top = ui.row();
        top.addView(ui.icon("back", "返回", AppViews.TEXT, v -> showMain()), toolbarButton());
        TextView heading = ellipsized("应用详情", 19, AppViews.TEXT, true);
        top.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(top, new LinearLayout.LayoutParams(-1, ui.dp(54)));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        root.addView(progress, new LinearLayout.LayoutParams(-1, ui.dp(3)));
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = ui.column();
        ui.pad(content, 0, 0, 0, 12);
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        DetailHeader hero = new DetailHeader(this, ui, title, subtitle, icon);
        LinearLayout.LayoutParams heroParams = new LinearLayout.LayoutParams(-1, -2);
        heroParams.setMargins(ui.dp(13), ui.dp(3), ui.dp(13), 0);
        content.addView(hero, heroParams);
        scroll.setOnScrollChangeListener((v, x, y, oldX, oldY) -> hero.parallax(y));
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1, -2);
        actionParams.setMargins(ui.dp(13), ui.dp(10), ui.dp(13), ui.dp(6));
        content.addView(actions, actionParams);
        TextView copyHint = ui.text("长按字段复制 · 长按卡片标题复制整组", 12, AppViews.MUTED, false);
        ui.pad(copyHint, 17, 2, 17, 4);
        content.addView(copyHint);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        pageHost.addView(root, new FrameLayout.LayoutParams(-1, -1));
        if (catalogProgress.getVisibility() == View.VISIBLE) catalogProgress.bringToFront();
        root.requestApplyInsets();
        showProgress();
        ui.enter(root, ui.dp(34), 0.55f, 245);
        hero.enter(ui);
        return content;
    }

    private void showApp(AppCatalog.App app) {
        detailKey = "app:" + app.packageName;
        LinearLayout content = detailPage(app.name, app.packageName, appIcon(app), appActions(app, true));
        DetailInfoCard basics = new DetailInfoCard(content, "基本信息");
        basics.field("包名", app.packageName, true);
        basics.field("版本", app.version + " (" + app.versionCode + ")", false);
        basics.field("首次安装", date(app.installedAt, true), false);
        basics.field("最后更新", date(app.updatedAt, true), false);

        DetailInfoCard apk = new DetailInfoCard(content, "APK 信息");
        apk.field("APK 路径", app.files.isEmpty() ? null : app.files.get(0).getAbsolutePath(), true);
        apk.field(app.files.size() > 1 ? "APK 总大小" : "APK 文件大小",
                app.bytes > 0 ? size(app.bytes) : null, false);
        if (app.files.size() > 1) {
            LinearLayout splits = ui.column();
            splits.setVisibility(View.GONE);
            int splitCount = app.files.size() - 1;
            TextView toggle = ui.text("展开拆分 APK（" + splitCount + "）", 13, AppViews.BLUE, true);
            ui.pad(toggle, 16, 10, 16, 10);
            toggle.setBackground(detailRipple(null, 6));
            toggle.setOnClickListener(v -> {
                boolean expand = splits.getVisibility() != View.VISIBLE;
                splits.setVisibility(expand ? View.VISIBLE : View.GONE);
                toggle.setText((expand ? "收起" : "展开") + "拆分 APK（" + splitCount + "）");
            });
            apk.addView(toggle);
            apk.addView(splits);
            for (int i = 1; i < app.files.size(); i++) {
                apk.field(splits, "拆分 APK " + i, app.files.get(i).getAbsolutePath(), true);
            }
        }

        DetailInfoCard data = new DetailInfoCard(content, "数据目录");
        data.field("私有数据目录", app.dataPath, true);
        File external = new File(Environment.getExternalStorageDirectory(), "Android/data/" + app.packageName);
        data.field("Android/data（主存储）", external.getAbsolutePath(), true);
        TextView directoryHint = ui.text("Android/data 路径按包名推算，目录可能尚未创建。", 12, AppViews.MUTED, false);
        ui.pad(directoryHint, 16, 0, 16, 6);
        data.addView(directoryHint);

        DetailInfoCard sdk = new DetailInfoCard(content, "SDK 与兼容性");
        sdk.field("编译 SDK", sdkLabel(app.compileSdk), false);
        sdk.field("最低兼容 SDK", sdkLabel(app.minSdk), false);
        sdk.field("目标 SDK", sdkLabel(app.targetSdk), false);
        String runningApi = String.valueOf(Build.VERSION.SDK_INT);
        if (Build.VERSION.SDK_INT >= 36) {
            int minor = Build.getMinorSdkVersion(Build.VERSION.SDK_INT_FULL);
            if (minor > 0) runningApi += "." + minor;
        }
        sdk.field("当前系统", "API " + runningApi + "（Android " + Build.VERSION.RELEASE + "）", false);

        DetailInfoCard nativeLibs = new DetailInfoCard(content, "应用原生库");
        nativeLibs.field("Lib 目录", app.nativeLibraryDir, true);
        TextView abiValue = nativeLibs.field("已安装 APK 的 ABI", "正在读取…", true);
        worker.execute(() -> {
            String abis = AppCatalog.installedApkAbis(app.files);
            runOnUiThread(() -> {
                if (!isDestroyed() && detail && content.isAttachedToWindow()) abiValue.setText(abis);
            });
        });

        DetailInfoCard device = new DetailInfoCard(content, "支持的 Lib");
        device.field(null, TextUtils.join("、", Build.SUPPORTED_ABIS), true);
        android.content.ComponentName component = app.launch != null ? app.launch.getComponent() : null;
        new DetailInfoCard(content, "启动组件")
                .field(null, component == null ? "无启动入口" : component.flattenToString(), true);
    }

    private void showBackup(BackupStore.Entry entry) {
        detailKey = "backup:" + entry.key();
        BackupStore.Metadata metadata = entry.metadata;
        LinearLayout content = detailPage(entry.label(), metadata == null ? "" : metadata.packageName,
                metadata != null ? metadata.newIcon()
                : getPackageManager().getDefaultActivityIcon(), backupActions(entry, true));
        DetailInfoCard basics = new DetailInfoCard(content, "基本信息");
        if (metadata != null) {
            basics.field("包名", metadata.packageName, true);
            basics.field("版本", metadata.version + " (" + metadata.versionCode + ")", false);
        }
        basics.field("安装状态", AppCatalog.BackupStatus.compare(entry,
                metadata == null ? null : installed.get(metadata.packageName)).label, false);
        DetailInfoCard file = new DetailInfoCard(content, "备份文件");
        file.field("文件名", entry.name, true);
        String path = entry.file != null ? entry.file.getAbsolutePath() : entry.uri.toString();
        file.field("文件位置", path, true);
        file.field("文件大小", size(entry.bytes), false);
        file.field("修改时间", date(entry.modified, true), false);
    }

    private RippleDrawable detailRipple(Drawable background, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x2917658E), background,
                ui.rounded(Color.WHITE, radius));
    }

    private boolean copyDetail(String label, CharSequence value) {
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
            toast("已复制" + label);
        }
        return true;
    }

    private final class DetailInfoCard extends LinearLayout {
        private final String title;
        private final Map<String, TextView> fields = new LinkedHashMap<>();

        DetailInfoCard(LinearLayout parent, String title) {
            super(MainActivity.this);
            this.title = title;
            setOrientation(VERTICAL);
            ui.pad(this, 0, 4, 0, 4);
            TextView heading = ui.text(title, 15, AppViews.TEXT, true);
            heading.setAccessibilityHeading(true);
            ui.pad(heading, 16, 8, 16, 3);
            addView(heading);
            setOnLongClickListener(v -> copyAll());
            parent.addView(cardFrame(this));
            setBackground(detailRipple(ui.rounded(Color.WHITE, 19), 19));
            setClipToOutline(true);
        }

        TextView field(String label, String value, boolean stacked) {
            return field(this, label, value, stacked);
        }

        TextView field(LinearLayout container, String label, String value, boolean stacked) {
            LinearLayout row = stacked ? ui.column() : ui.row();
            ui.pad(row, 16, 7, 16, 7);
            TextView text = ui.text(TextUtils.isEmpty(value) ? "未提供" : value, 14, AppViews.TEXT, false);
            if (label != null) {
                TextView caption = ui.text(label, 12, AppViews.MUTED, false);
                if (stacked) {
                    row.addView(caption);
                    ui.pad(text, 0, 2, 0, 0);
                } else {
                    row.addView(caption, new LinearLayout.LayoutParams(0, -2, 2));
                    text.setGravity(Gravity.END);
                    ui.pad(text, 8, 0, 0, 0);
                }
            }
            row.addView(text, stacked ? new LinearLayout.LayoutParams(-1, -2)
                    : new LinearLayout.LayoutParams(0, -2, 3));
            row.setBackground(detailRipple(null, 6));
            row.setOnLongClickListener(v -> copyDetail(label == null ? title : label, text.getText()));
            container.addView(row, new LinearLayout.LayoutParams(-1, -2));
            fields.put(label, text);
            return text;
        }

        private boolean copyAll() {
            StringBuilder value = new StringBuilder();
            fields.forEach((label, field) -> {
                if (value.length() > 0) value.append('\n');
                if (label != null && fields.size() > 1) value.append(label).append("：");
                value.append(field.getText());
            });
            return copyDetail(title, value);
        }
    }

    private void launch(AppCatalog.App app) {
        if (app.launch == null) {
            toast("此应用没有可启动的界面");
            return;
        }
        try { startActivity(app.launch); }
        catch (RuntimeException error) { toast("无法打开 " + app.name); }
    }

    private void uninstall(AppCatalog.App app) {
        try { startActivity(new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.packageName, null))); }
        catch (RuntimeException error) { toast("无法启动系统卸载"); }
    }

    private void market(String packageName) {
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + Uri.encode(packageName)));
        List<ResolveInfo> stores = getPackageManager().queryIntentActivities(view, 0);
        if (!stores.isEmpty()) {
            try {
                startActivity(stores.size() == 1 ? view : Intent.createChooser(view, "选择应用市场"));
                return;
            } catch (RuntimeException ignored) { }
        }
        new AlertDialog.Builder(this).setTitle("没有可用的应用市场")
                .setMessage(packageName)
                .setPositiveButton("复制包名", (d, w) -> {
                    getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("包名", packageName));
                    if (Build.VERSION.SDK_INT < 33) toast("已复制包名");
                }).setNegativeButton("关闭", null).show();
    }

    private void share(File file) {
        Uri uri = SharedFileProvider.uri(this, file);
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType(file.getName().toLowerCase(Locale.ROOT).endsWith(".apk") ? BackupStore.APK_MIME : "application/zip")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newUri(getContentResolver(), file.getName(), uri));
        try { startActivity(Intent.createChooser(send, "分享安装包")); }
        catch (RuntimeException error) { toast("无法打开分享菜单"); }
    }

    private void confirmInstall(BackupStore.Entry entry) {
        if (entry.metadata == null) {
            toast("无法识别此安装包");
            return;
        }
        if (!getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(this).setTitle("允许安装应用")
                    .setMessage("请在系统设置中允许「应用管理」安装应用，然后返回继续。")
                    .setPositiveButton("前往设置", (dialog, which) -> {
                        pendingInstall = entry;
                        try {
                            startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.fromParts("package", getPackageName(), null)), ALLOW_INSTALL);
                        } catch (RuntimeException error) {
                            pendingInstall = null;
                            toast("无法打开安装权限设置");
                        }
                    }).setNegativeButton("取消", null).show();
            return;
        }
        AppCatalog.App current = installed.get(entry.metadata.packageName);
        String installedVersion = current == null ? "未安装" : current.version + " (" + current.versionCode + ")";
        String versions = "当前版本  " + installedVersion + "\n备份版本  "
                + entry.metadata.version + " (" + entry.metadata.versionCode + ")";
        if (current != null && entry.metadata.versionCode < current.versionCode) {
            versions += "\n\n系统可能拒绝降级安装。";
        }
        new AlertDialog.Builder(this).setTitle("安装 " + entry.label()).setMessage(versions)
                .setPositiveButton("继续安装", (dialog, which) -> perform(() -> {
                    ApkInstaller.install(this, store, entry);
                    return null;
                }, result -> { })).setNegativeButton("取消", null).show();
    }

    private void confirmDelete(BackupStore.Entry entry) {
        new AlertDialog.Builder(this).setTitle("删除备份").setMessage("删除 " + entry.name + "？")
                .setPositiveButton("删除", (d, w) -> perform(() -> {
                    store.delete(entry);
                    return null;
                }, result -> {
                    String key = "backup:" + entry.key();
                    if (key.equals(detailKey)) showMain();
                    if (backups != null) {
                        backups = backups.without(entry);
                        pages[BACKUP].render(false, Collections.singleton(key));
                        updateTabCounts();
                    }
                    toast("已删除备份");
                })).setNegativeButton("取消", null).show();
    }

    private <T> void perform(Callable<T> task, Consumer<T> success) {
        if (operating) {
            toast("请等待当前操作完成");
            return;
        }
        operating = true;
        showProgress();
        worker.execute(() -> {
            try {
                T result = task.call();
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    operating = false;
                    showProgress();
                    success.accept(result);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isDestroyed()) return;
                    operating = false;
                    showProgress();
                    toast("操作失败：" + message(error));
                });
            }
        });
    }

    private static String message(Throwable error) {
        String text = error.getMessage();
        return text == null || text.isEmpty() ? error.getClass().getSimpleName() : text;
    }

    private static String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.ROOT, "%.2f %s", value, units[unit]);
    }

    private static String sdkLabel(int api) {
        if (api <= 0) return "系统未提供";
        return "API " + api + "（Android " + androidVersion(api) + "）";
    }

    private static String androidVersion(int api) {
        switch (api) {
            case 1: return "1.0";
            case 2: return "1.1";
            case 3: return "1.5";
            case 4: return "1.6";
            case 5: return "2.0";
            case 6: return "2.0.1";
            case 7: return "2.1";
            case 8: return "2.2";
            case 9: return "2.3";
            case 10: return "2.3.3";
            case 11: return "3.0";
            case 12: return "3.1";
            case 13: return "3.2";
            case 14: return "4.0";
            case 15: return "4.0.3";
            case 16: return "4.1";
            case 17: return "4.2";
            case 18: return "4.3";
            case 19: return "4.4";
            case 20: return "4.4W";
            case 21: return "5.0";
            case 22: return "5.1";
            case 23: return "6.0";
            case 24: return "7.0";
            case 25: return "7.1";
            case 26: return "8.0";
            case 27: return "8.1";
            case 28: return "9.0";
            case 29: return "10";
            case 30: return "11";
            case 31: return "12";
            case 32: return "12L";
            case 33: return "13";
            case 34: return "14";
            case 35: return "15";
            case 36: return "16";
            case 37: return "17";
            default: return "未知版本";
        }
    }

    private String date(long timestamp, boolean exact) {
        if (timestamp <= 0) return "—";
        return (exact ? fullDate : shortDate).format(new Date(timestamp));
    }

    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_SHORT).show(); }
}


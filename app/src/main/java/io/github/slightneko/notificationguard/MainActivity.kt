package io.github.slightneko.notificationguard

import android.app.AlertDialog
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import io.github.slightneko.notificationguard.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

class MainActivity : ComponentActivity() {
    private val repo by lazy { UiRepository(this) }
    private val preferences by lazy { createDeviceProtectedStorageContext().getSharedPreferences("ui",MODE_PRIVATE) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MiuixTheme(colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) { GuardApp() }
        }
    }
    private fun confirm(title: String, message: String, action: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setNegativeButton("取消",null).setPositiveButton("确认") { _,_ -> action() }.show()
    }
    @Composable private fun GuardApp() {
        val scope = rememberCoroutineScope()
        var snapshot by remember { mutableStateOf(UiSnapshot()) }
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var days by rememberSaveable { mutableIntStateOf(1) }
        var appPkg by rememberSaveable { mutableStateOf<String?>(null) }
        var appUser by rememberSaveable { mutableIntStateOf(0) }
        var search by rememberSaveable { mutableStateOf("") }
        var filter by rememberSaveable { mutableIntStateOf(0) }
        var nonce by remember { mutableIntStateOf(0) }
        var loading by remember { mutableStateOf(true) }
        var onboarding by rememberSaveable { mutableStateOf(!preferences.getBoolean("onboarded",false)) }
        var error by remember { mutableStateOf("") }
        fun execute(action: () -> Unit) {
            scope.launch {
                try { withContext(Dispatchers.IO) { action() }; nonce++ }
                catch (e: Exception) { Toast.makeText(this@MainActivity,"操作失败，请检查模块状态",Toast.LENGTH_LONG).show() }
            }
        }
        LaunchedEffect(days,nonce) {
            while (true) {
                try { snapshot = withContext(Dispatchers.IO) { repo.load(days) }; error = "" }
                catch (e: Exception) { error = "数据读取失败" }
                loading = false
                delay(3000)
            }
        }
        val now = System.currentTimeMillis()
        val systemOnline = snapshot.state["system"]?.toLongOrNull()?.let { now - it < 30000 } == true
        val uiOnline = snapshot.state["systemui"]?.toLongOrNull()?.let { now - it < 30000 } == true
        val busy = snapshot.jobs.any { it.status == "pending" || it.status == "running" }
        val selectedChannels = snapshot.channels.filter { it.pkg == appPkg && it.user == appUser }
        val appTitle = selectedChannels.firstOrNull()?.app ?: snapshot.stats.firstOrNull { it.pkg == appPkg && it.user == appUser }?.app ?: appPkg.orEmpty()
        BackHandler(enabled = appPkg != null) { appPkg = null; search = "" }
        Scaffold(
            topBar = {
                TopAppBar(title = if (onboarding) "初始化" else if (appPkg != null) appTitle else "通知管家",
                    navigationIcon = { if (appPkg != null) IconButton(onClick = { appPkg = null; search = "" }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"返回") } },
                    actions = { if (!onboarding) IconButton(onClick = { execute { repo.request("refresh") } }, enabled = systemOnline && !busy) { Icon(Icons.Outlined.Refresh,"刷新渠道") } })
            },
            bottomBar = {
                if (!onboarding) NavigationBar {
                    NavigationBarItem(selected = tab == 0,onClick = { tab = 0; appPkg = null; search = "" },icon = Icons.Outlined.BarChart,label = "排行")
                    NavigationBarItem(selected = tab == 1,onClick = { tab = 1; appPkg = null; search = "" },icon = Icons.AutoMirrored.Outlined.List,label = "渠道")
                    NavigationBarItem(selected = tab == 2,onClick = { tab = 2; appPkg = null; search = "" },icon = Icons.Outlined.Settings,label = "设置")
                }
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (error.isNotEmpty()) Text(error,Modifier.padding(20.dp),color = Color(0xFFC74747))
                when {
                    onboarding -> Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        StatusLine("系统通知服务",systemOnline)
                        StatusLine("SystemUI",uiOnline)
                        Text("API 102",fontSize = 14.sp,color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        TextButton("一键开启全部通知",onClick = { confirm("开启所有通知？","将开启全部 App 的通知权限、渠道组和已有渠道，包括系统 App。可能立即出现声音、振动和营销通知。原状态会先备份。") { execute { repo.request("enable") } } },enabled = systemOnline && !busy,modifier = Modifier.fillMaxWidth(),cornerRadius = 8.dp)
                        TextButton("进入",onClick = { preferences.edit().putBoolean("onboarded",true).apply(); onboarding = false; if (systemOnline && !busy && snapshot.channels.isEmpty()) execute { repo.request("refresh") } },modifier = Modifier.fillMaxWidth(),cornerRadius = 8.dp)
                        snapshot.jobs.firstOrNull()?.let { JobView(it) }
                    }
                    tab == 0 -> {
                        Segments(listOf("今日","7 天","30 天","累计"),listOf(1,7,30,0).indexOf(days)) { days = listOf(1,7,30,0)[it] }
                        val rows = snapshot.stats.filter { appPkg == null || (it.pkg == appPkg && it.user == appUser) }
                        Row(Modifier.fillMaxWidth().padding(20.dp),horizontalArrangement = Arrangement.SpaceBetween) {
                            Counter("发送尝试",rows.sumOf { it.attempts },Color(0xFF16866D),Modifier.weight(1f))
                            Counter("拦截",rows.sumOf { it.blocked },Color(0xFFCF5F51),Modifier.weight(1f))
                            Counter("更新",rows.sumOf { it.updates },Color(0xFF687CC2),Modifier.weight(1f))
                        }
                        if (loading) EmptyView("加载中")
                        else if (rows.isEmpty()) EmptyView(if (systemOnline) "暂无记录" else "系统服务未连接")
                        else LazyColumn(Modifier.weight(1f)) {
                            if (appPkg == null) items(aggregateApps(rows),key = { "${it.user}:${it.pkg}" }) { row ->
                                AppRow(row.pkg,row.app,row.user,"${row.attempts} 次尝试 · ${row.blocked} 次拦截") { appPkg = row.pkg; appUser = row.user }
                            } else {
                                item { TextButton("管理渠道",onClick = { tab = 1 },modifier = Modifier.padding(horizontal = 20.dp),cornerRadius = 8.dp) }
                                items(rows,key = { it.channel }) { row ->
                                    Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text(row.label.ifEmpty { "未指定渠道" },fontWeight = FontWeight.Medium)
                                        Text("${row.attempts} 次尝试 · ${row.blocked} 次拦截 · ${row.updates} 次更新",fontSize = 13.sp,color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                                        DividerLine()
                                    }
                                }
                            }
                        }
                    }
                    tab == 1 -> {
                        TextField(value = search,onValueChange = { search = it },label = "搜索应用或渠道",singleLine = true,modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp,vertical = 8.dp),cornerRadius = 8.dp)
                        Segments(listOf("全部","已拦截","已隐藏"),filter) { filter = it }
                        val rows = snapshot.channels.filter { row ->
                            (appPkg == null || row.pkg == appPkg && row.user == appUser) &&
                                (filter == 0 || filter == 1 && row.blocked || filter == 2 && row.hidden) &&
                                (search.isBlank() || row.app.contains(search,true) || row.pkg.contains(search,true) || row.label.contains(search,true) || row.channel.contains(search,true))
                        }
                        if (rows.isEmpty()) EmptyView(if (loading) "加载中" else if (snapshot.channels.isEmpty()) "尚未获取渠道" else "没有匹配的渠道")
                        else LazyColumn(Modifier.weight(1f)) {
                            if (appPkg == null) items(rows.groupBy { it.user to it.pkg }.values.toList(),key = { "${it.first().user}:${it.first().pkg}" }) { group ->
                                val row = group.first()
                                AppRow(row.pkg,row.app,row.user,"${group.count { it.channel.isNotEmpty() }} 个渠道 · ${group.count { it.blocked }} 个拦截") { appPkg = row.pkg; appUser = row.user }
                            } else items(rows,key = { it.channel }) { row ->
                                ChannelView(row) { blocked,hidden -> execute { repo.rule(row,blocked,hidden) } }
                            }
                        }
                    }
                    else -> LazyColumn(Modifier.fillMaxSize(),contentPadding = PaddingValues(20.dp),verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        item { StatusLine("系统通知服务",systemOnline) }
                        item { Text(snapshot.state["system_detail"].orEmpty(),fontSize = 13.sp,color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                        item { StatusLine("SystemUI",uiOnline) }
                        item { Text(snapshot.state["systemui_detail"].orEmpty(),fontSize = 13.sp,color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                        item { DividerLine() }
                        item { TextButton("一键开启全部通知",onClick = { confirm("开启所有通知？","将开启全部 App 的通知权限、渠道组和已有渠道，包括系统 App。原状态会先备份，声音、振动等其他设置不变。") { execute { repo.request("enable") } } },enabled = systemOnline && !busy,modifier = Modifier.fillMaxWidth(),cornerRadius = 8.dp) }
                        item { TextButton("恢复通知设置（${snapshot.backups} 个 App）",onClick = { confirm("恢复原设置？","将按备份恢复通知开关与已有渠道。失败项会保留备份；模块拦截规则不变。") { execute { repo.request("restore") } } },enabled = systemOnline && !busy && snapshot.backups > 0,modifier = Modifier.fillMaxWidth(),cornerRadius = 8.dp) }
                        item { TextButton("清空统计",onClick = { confirm("清空统计？","此操作无法撤销，渠道规则不会删除。") { execute { repo.clearStats() } } },modifier = Modifier.fillMaxWidth(),cornerRadius = 8.dp) }
                        item { Text("任务记录",fontWeight = FontWeight.SemiBold) }
                        items(snapshot.jobs) { JobView(it) }
                        item { Text("NotificationGuard ${BuildConfig.VERSION_NAME} · API 102",fontSize = 13.sp,color = MiuixTheme.colorScheme.onSurfaceVariantSummary) }
                    }
                }
            }
        }
    }
    @Composable private fun AppRow(pkg: String,name: String,user: Int,subtitle: String,onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp,vertical = 16.dp),verticalAlignment = Alignment.CenterVertically,horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val bitmap = remember(pkg) { runCatching { packageManager.getApplicationIcon(pkg).toBitmap(96,96).asImageBitmap() }.getOrNull() }
            if (bitmap != null) Image(bitmap,null,Modifier.size(40.dp)) else Icon(Icons.AutoMirrored.Outlined.List,null,Modifier.size(40.dp))
            Column(Modifier.weight(1f),verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(name,fontWeight = FontWeight.Medium)
                Text(subtitle + if (user != 0) " · 用户 $user" else "",fontSize = 13.sp,color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            Icon(Icons.Outlined.ChevronRight,"查看",Modifier.size(20.dp))
        }
        DividerLine()
    }
    @Composable private fun ChannelView(row: ChannelRow,onChange: (Boolean,Boolean) -> Unit) {
        Column(Modifier.fillMaxWidth().padding(20.dp),verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(row.label,fontWeight = FontWeight.SemiBold)
            Text(row.channel,fontSize = 12.sp,color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            if (!row.enabled || row.importance == 0) Text("系统通知开关已关闭",fontSize = 13.sp,color = Color(0xFFCF5F51))
            if (row.channel.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("拦截",Modifier.weight(1f)); Switch(checked = row.blocked,onCheckedChange = { onChange(it,row.hidden) }) }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("隐藏状态栏图标",Modifier.weight(1f)); Switch(checked = row.hidden,onCheckedChange = { onChange(row.blocked,it) }) }
            }
            DividerLine()
        }
    }
    @Composable private fun Counter(label: String,value: Long,color: Color,modifier: Modifier) {
        Column(modifier,verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value.toString(),modifier = Modifier.fillMaxWidth(),maxLines = 1,autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 8.sp,maxFontSize = 26.sp),fontWeight = FontWeight.SemiBold,color = color)
            Text(label,fontSize = 12.sp,color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
    @Composable private fun StatusLine(label: String,online: Boolean) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.SpaceBetween) { Text(label); Text(if (online) "已连接" else "未连接",color = if (online) Color(0xFF16866D) else Color(0xFFCF5F51)) }
    }
    @Composable private fun Segments(labels: List<String>,selected: Int,onSelect: (Int) -> Unit) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp,vertical = 8.dp).selectableGroup()) {
            labels.forEachIndexed { index,label ->
                Column(Modifier.weight(1f).selectable(selected == index,role = Role.Tab,onClick = { onSelect(index) }).padding(vertical = 10.dp),horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label,fontSize = 14.sp,fontWeight = if (selected == index) FontWeight.SemiBold else FontWeight.Normal)
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.width(24.dp).height(2.dp).background(if (selected == index) Color(0xFF16866D) else Color.Transparent))
                }
            }
        }
    }
    @Composable private fun EmptyView(text: String) { Box(Modifier.fillMaxWidth().padding(40.dp),contentAlignment = Alignment.Center) { Text(text,color = MiuixTheme.colorScheme.onSurfaceVariantSummary) } }
    @Composable private fun DividerLine() { Box(Modifier.fillMaxWidth().height(1.dp).background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.08f))) }
    @Composable private fun JobView(job: JobRow) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(when(job.action) { "enable" -> "开启全部通知"; "restore" -> "恢复通知设置"; else -> "刷新渠道" } + " · " + when(job.status) { "pending" -> "等待执行"; "running" -> "执行中"; "done" -> "完成"; "partial" -> "部分失败"; "failed" -> "失败"; else -> "已中断" },fontSize = 14.sp,fontWeight = FontWeight.Medium)
            Text(job.detail,fontSize = 12.sp,color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            DividerLine()
        }
    }
}

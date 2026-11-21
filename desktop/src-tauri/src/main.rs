#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use rand::RngCore;
use serde::Serialize;
use std::env;
use std::fs;
use std::net::TcpListener;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;
use tauri::menu::{Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Manager, RunEvent, State, WindowEvent};
use tauri_plugin_autostart::{MacosLauncher, ManagerExt};

#[cfg(windows)]
use std::os::windows::io::AsRawHandle;
#[cfg(windows)]
use std::os::windows::process::CommandExt;

#[cfg(windows)]
const CREATE_NO_WINDOW: u32 = 0x0800_0000;

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct DesktopBootstrap {
    desktop_mode: bool,
    api_base_url: String,
    desktop_token: String,
    data_dir: String,
    version: String,
}

#[cfg(windows)]
struct WindowsJob(windows_sys::Win32::Foundation::HANDLE);

#[cfg(windows)]
unsafe impl Send for WindowsJob {}

#[cfg(windows)]
impl Drop for WindowsJob {
    fn drop(&mut self) {
        if !self.0.is_null() {
            unsafe {
                windows_sys::Win32::Foundation::CloseHandle(self.0);
            }
        }
    }
}

struct BackendProcess {
    child: Option<Child>,
    #[cfg(windows)]
    _job: Option<WindowsJob>,
}

impl BackendProcess {
    fn stop(&mut self) {
        if let Some(mut child) = self.child.take() {
            let _ = child.kill();
            let _ = child.wait();
        }
        #[cfg(windows)]
        {
            self._job = None;
        }
    }
}

struct DesktopState {
    bootstrap: DesktopBootstrap,
    data_dir: PathBuf,
    backend: Mutex<BackendProcess>,
}

fn generate_handshake_token() -> String {
    let mut bytes = [0u8; 32];
    rand::thread_rng().fill_bytes(&mut bytes);
    let mut out = String::with_capacity(64);
    for b in bytes {
        use std::fmt::Write as _;
        let _ = write!(&mut out, "{:02x}", b);
    }
    out
}

fn pick_free_loopback_port() -> u16 {
    if let Ok(raw) = env::var("FLAME_DESKTOP_PORT") {
        if let Ok(port) = raw.trim().parse::<u16>() {
            if port > 0 && TcpListener::bind(("127.0.0.1", port)).is_ok() {
                return port;
            }
        }
    }
    TcpListener::bind(("127.0.0.1", 0))
        .and_then(|listener| listener.local_addr())
        .map(|addr| addr.port())
        .unwrap_or(18420)
}

#[cfg(all(windows, not(debug_assertions)))]
fn is_dir_writable(dir: &Path) -> bool {
    if fs::create_dir_all(dir).is_err() {
        return false;
    }
    let probe = dir.join(format!(".flame_write_probe_{}", std::process::id()));
    match fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&probe)
    {
        Ok(_) => {
            let _ = fs::remove_file(&probe);
            true
        }
        Err(_) => false,
    }
}

fn resolve_data_dir(app: &AppHandle) -> PathBuf {
    if let Ok(raw) = env::var("FLAME_DATA_DIR") {
        let trimmed = raw.trim();
        if !trimmed.is_empty() {
            let path = PathBuf::from(trimmed);
            let _ = fs::create_dir_all(&path);
            return path;
        }
    }
    // Windows 正式安装版优先跟随软件安装目录存放 data，避免占用 C 盘系统目录；
    // 若安装到 Program Files 等只读目录，则自动回退到 %LOCALAPPDATA%\FlameContractor。
    #[cfg(all(windows, not(debug_assertions)))]
    if let Ok(cur_exe) = env::current_exe() {
        if let Some(install_dir) = cur_exe.parent() {
            let portable = install_dir.join("data");
            if is_dir_writable(&portable) {
                return portable;
            }
        }
    }
    #[cfg(windows)]
    if let Ok(local_app_data) = env::var("LOCALAPPDATA") {
        let trimmed = local_app_data.trim();
        if !trimmed.is_empty() {
            let path = PathBuf::from(trimmed).join("FlameContractor");
            let _ = fs::create_dir_all(&path);
            return path;
        }
    }
    let fallback = app
        .path()
        .app_local_data_dir()
        .unwrap_or_else(|_| env::temp_dir().join("FlameContractor"));
    let _ = fs::create_dir_all(&fallback);
    fallback
}

fn resolve_backend_executable(app: &AppHandle) -> Option<PathBuf> {
    if let Ok(explicit) = env::var("FLAME_DESKTOP_BACKEND_EXE") {
        let path = PathBuf::from(explicit.trim());
        if path.is_file() {
            return Some(path);
        }
    }

    let exe_name = if cfg!(windows) {
        "flame-backend.exe"
    } else {
        "flame-backend"
    };

    let mut candidates: Vec<PathBuf> = Vec::new();
    if let Ok(res_dir) = app.path().resource_dir() {
        candidates.push(res_dir.join("backend").join(exe_name));
        candidates.push(res_dir.join("resources").join("backend").join(exe_name));
    }
    if let Ok(cur_exe) = env::current_exe() {
        if let Some(parent) = cur_exe.parent() {
            candidates.push(parent.join("backend").join(exe_name));
            candidates.push(parent.join("resources").join("backend").join(exe_name));
        }
    }
    if let Ok(cwd) = env::current_dir() {
        candidates.push(cwd.join("resources").join("backend").join(exe_name));
        candidates.push(
            cwd.join("src-tauri")
                .join("resources")
                .join("backend")
                .join(exe_name),
        );
        candidates.push(
            cwd.join("desktop")
                .join("src-tauri")
                .join("resources")
                .join("backend")
                .join(exe_name),
        );
    }

    candidates.into_iter().find(|path| path.is_file())
}

#[cfg(windows)]
fn attach_kill_on_close_job(child: &Child) -> Option<WindowsJob> {
    use std::mem::{size_of, zeroed};
    use windows_sys::Win32::System::JobObjects::{
        AssignProcessToJobObject, CreateJobObjectW, JobObjectExtendedLimitInformation,
        SetInformationJobObject, JOBOBJECT_EXTENDED_LIMIT_INFORMATION,
        JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
    };

    unsafe {
        let job = CreateJobObjectW(std::ptr::null(), std::ptr::null());
        if job.is_null() {
            return None;
        }
        let mut info: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = zeroed();
        info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        let ok_info = SetInformationJobObject(
            job,
            JobObjectExtendedLimitInformation,
            &info as *const _ as *const _,
            size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
        );
        if ok_info == 0 {
            windows_sys::Win32::Foundation::CloseHandle(job);
            return None;
        }
        let ok_assign = AssignProcessToJobObject(job, child.as_raw_handle() as _);
        if ok_assign == 0 {
            windows_sys::Win32::Foundation::CloseHandle(job);
            return None;
        }
        Some(WindowsJob(job))
    }
}

fn spawn_backend(
    backend_exe: &Path,
    port: u16,
    data_dir: &Path,
    desktop_token: &str,
) -> std::io::Result<BackendProcess> {
    let mut cmd = Command::new(backend_exe);
    cmd.env("FLAME_PORT", port.to_string())
        .env("FLAME_DATA_DIR", data_dir)
        .env("FLAME_DESKTOP_MODE", "true")
        .env("FLAME_DESKTOP_TOKEN", desktop_token)
        .env("FLAME_SERVE_FRONTEND", "false")
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());

    #[cfg(windows)]
    {
        cmd.creation_flags(CREATE_NO_WINDOW);
    }

    let child = cmd.spawn()?;
    #[cfg(windows)]
    let job = attach_kill_on_close_job(&child);

    Ok(BackendProcess {
        child: Some(child),
        #[cfg(windows)]
        _job: job,
    })
}

fn focus_main_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
}

fn open_directory_in_shell(path: &Path) {
    let _ = fs::create_dir_all(path);
    #[cfg(windows)]
    {
        let _ = Command::new("explorer").arg(path).spawn();
    }
    #[cfg(target_os = "macos")]
    {
        let _ = Command::new("open").arg(path).spawn();
    }
    #[cfg(all(unix, not(target_os = "macos")))]
    {
        let _ = Command::new("xdg-open").arg(path).spawn();
    }
}

#[tauri::command]
fn get_desktop_bootstrap(state: State<'_, DesktopState>) -> DesktopBootstrap {
    state.bootstrap.clone()
}

#[tauri::command]
fn get_autostart(app: AppHandle) -> Result<bool, String> {
    app.autolaunch().is_enabled().map_err(|e| e.to_string())
}

#[tauri::command]
fn set_autostart(app: AppHandle, enabled: bool) -> Result<bool, String> {
    let manager = app.autolaunch();
    if enabled {
        manager.enable().map_err(|e| e.to_string())?;
    } else {
        manager.disable().map_err(|e| e.to_string())?;
    }
    manager.is_enabled().map_err(|e| e.to_string())
}

#[tauri::command]
fn open_data_dir(state: State<'_, DesktopState>) {
    open_directory_in_shell(&state.data_dir);
}

fn main() {
    let app = tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            focus_main_window(app);
        }))
        .plugin(tauri_plugin_autostart::init(
            MacosLauncher::LaunchAgent,
            None,
        ))
        .invoke_handler(tauri::generate_handler![
            get_desktop_bootstrap,
            get_autostart,
            set_autostart,
            open_data_dir,
        ])
        .setup(|app| {
            let handle = app.handle().clone();
            let data_dir = resolve_data_dir(&handle);
            let port = pick_free_loopback_port();
            let token = generate_handshake_token();

            let backend_proc = match resolve_backend_executable(&handle) {
                Some(exe) => match spawn_backend(&exe, port, &data_dir, &token) {
                    Ok(proc) => proc,
                    Err(err) => {
                        eprintln!("启动内置后端失败 ({}): {}", exe.display(), err);
                        BackendProcess {
                            child: None,
                            #[cfg(windows)]
                            _job: None,
                        }
                    }
                },
                None => {
                    eprintln!("未找到打包后的 flame-backend 可执行文件");
                    BackendProcess {
                        child: None,
                        #[cfg(windows)]
                        _job: None,
                    }
                }
            };

            let bootstrap = DesktopBootstrap {
                desktop_mode: true,
                api_base_url: format!("http://127.0.0.1:{port}"),
                desktop_token: token,
                data_dir: data_dir.to_string_lossy().to_string(),
                version: app.package_info().version.to_string(),
            };

            app.manage(DesktopState {
                bootstrap,
                data_dir: data_dir.clone(),
                backend: Mutex::new(backend_proc),
            });

            // 系统托盘菜单：关闭窗口最小化到托盘，只有点「彻底退出」才停后端
            let open_item = MenuItem::with_id(app, "open", "打开主界面", true, None::<&str>)?;
            let dir_item = MenuItem::with_id(app, "data_dir", "打开数据目录", true, None::<&str>)?;
            let sep = PredefinedMenuItem::separator(app)?;
            let quit_item = MenuItem::with_id(app, "quit", "彻底退出", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&open_item, &dir_item, &sep, &quit_item])?;

            let mut tray_builder = TrayIconBuilder::new()
                .menu(&menu)
                .tooltip("Flame Contractor · 焰之契约者")
                .show_menu_on_left_click(false)
                .on_menu_event(|app, event| match event.id.as_ref() {
                    "open" => focus_main_window(app),
                    "data_dir" => {
                        if let Some(state) = app.try_state::<DesktopState>() {
                            open_directory_in_shell(&state.data_dir);
                        }
                    }
                    "quit" => {
                        if let Some(state) = app.try_state::<DesktopState>() {
                            if let Ok(mut guard) = state.backend.lock() {
                                guard.stop();
                            }
                        }
                        app.exit(0);
                    }
                    _ => {}
                })
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::Click {
                        button: MouseButton::Left,
                        button_state: MouseButtonState::Up,
                        ..
                    } = event
                    {
                        focus_main_window(tray.app_handle());
                    }
                });

            if let Some(icon) = app.default_window_icon().cloned() {
                tray_builder = tray_builder.icon(icon);
            }
            tray_builder.build(app)?;

            Ok(())
        })
        .on_window_event(|window, event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                // 关闭主窗口时最小化到右下角托盘，保持后台定时续火运行
                api.prevent_close();
                let _ = window.hide();
            }
        })
        .build(tauri::generate_context!())
        .expect("构建 Flame Contractor 桌面应用失败");

    app.run(|app_handle, event| {
        if let RunEvent::Exit = event {
            if let Some(state) = app_handle.try_state::<DesktopState>() {
                if let Ok(mut guard) = state.backend.lock() {
                    guard.stop();
                }
            }
        }
    });
}

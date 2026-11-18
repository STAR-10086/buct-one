# 界面原型

`dashboard.html` 是工作台首屏的可运行原型，用浏览器直接打开即可。
它不是截图，也不是 Pillow 手绘——是真实的 HTML/CSS，玻璃效果用 `backdrop-filter: blur(26px) saturate(180%)`，
与 Compose 侧要复现的材质同源。

改设计时改这个文件，然后：

```bash
"C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe" \
  --headless=new --disable-gpu --hide-scrollbars \
  --force-device-scale-factor=2 --window-size=880,900 \
  --screenshot=docs/mockups/dashboard.png \
  "file:///<repo>/docs/mockups/dashboard.html"
```

深色与浅色两台并排在同一页里，改一次两边同时生效。

## 与 Compose 实现的差异

| 能力 | HTML 原型 | Compose（M1） | Compose（M2 目标） |
|---|---|---|---|
| 背景模糊 | `backdrop-filter` 原生支持 | 不支持，用「半透明 + 0.5dp 高光边」近似 | GraphicsLayer 抓帧 + RenderEffect |
| 圆角 | `border-radius` | `RoundedCornerShape` | 同 |
| 字体 | 系统字体栈 | 系统字体 | 同 |
| 动效 | CSS transition | `MotionDuration` token | 同 |

因此原型里的「玻璃感」比 M1 真机会更强，M2 才追平。

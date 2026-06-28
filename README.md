# PlayerMonitor - 玩家数据监控面板

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Java](https://img.shields.io/badge/Java-21-blue)](https://adoptium.net/)

一个基于 Bukkit/Spigot/Paper 的 Minecraft 服务器插件，提供网页端玩家数据监控面板。

## 功能特性

- 实时查看玩家数据（血量、饥饿值、经验值、坐标等）
- 支持离线玩家数据查看
- 查看玩家背包、护甲、副手、末影箱
- 统计信息（游玩时间、击杀/死亡、旅行距离等）
- 响应式 Web 界面，手机/电脑自适应
- 支持 Vault 经济插件（需要在配置里面设置）

## 兼容性

- Minecraft 1.16.x - 1.21.x
- 服务端: Paper / Spigot / Purpur

## 安装

1. 下载最新 [Release](https://github.com/你的用户名/PlayerMonitor/releases)
2. 放入 `plugins` 文件夹
3. 重启服务器
4. 访问 `http://你的服务器IP:8080`

## 命令

- `/playermonitor reload` - 重载配置
- `/playermonitor info` - 查看插件信息

## 配置文件

### `plugins/PlayerMonitor/item_names.json`
物品中文名称映射表，可自定义添加。

### `plugins/PlayerMonitor/playtime.json`
玩家在线时间数据（自动生成）。

## 构建

```bash
mvn clean package

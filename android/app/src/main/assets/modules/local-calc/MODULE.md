---
id: local_calc
name: 本地计算
description: 本地计算。用户说"算一下""等于多少""换算成""几进制""百分之几"时使用。支持算数、进制转换、百分比、温度与单位换算，不联网。
version: 1.0.0
hooks: [input]
inject: none
capabilities: {}
entry: script/main.js
---

钩子 A。匹配纯算式时返回 local，短路整条网络链路。

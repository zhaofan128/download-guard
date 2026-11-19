/* 下载守护官网 —— 脚本只有三件事：填配置、收窄屏菜单、提示未填项。
   没有任何埋点、没有任何外部请求。 */
(function () {
  "use strict";

  var SITE = window.SITE || {};
  var PLACEHOLDER = /【[^】]*】/;

  function isTodo(value) {
    return !value || PLACEHOLDER.test(String(value));
  }

  function fill() {
    // 文本占位：<span data-site="operator">…</span>
    document.querySelectorAll("[data-site]").forEach(function (el) {
      var key = el.getAttribute("data-site");
      var value = SITE[key];
      if (value !== undefined && value !== null && String(value) !== "") {
        el.textContent = String(value);
        if (key === "operator" || key === "email") {
          el.classList.remove("todo");
        }
      }
    });

    // 邮件链接：<a data-site-mail="email">…</a>
    document.querySelectorAll("[data-site-mail]").forEach(function (el) {
      var key = el.getAttribute("data-site-mail");
      var value = SITE[key];
      if (!isTodo(value)) {
        el.setAttribute("href", "mailto:" + value);
        if (!el.textContent.trim()) el.textContent = value;
      } else {
        el.setAttribute("href", "#");
        el.setAttribute("aria-disabled", "true");
      }
    });

    // 发布页：<a data-site-href="release">
    document.querySelectorAll("[data-site-href]").forEach(function (el) {
      var key = el.getAttribute("data-site-href");
      if (key === "release") el.setAttribute("href", SITE.repo + "/releases/latest");
      if (key === "apk") el.setAttribute("href", SITE.apkRelease || (SITE.repo + "/releases/tag/v3.4.0"));
      if (key === "desktop") el.setAttribute("href", SITE.desktopRelease || (SITE.repo + "/releases/latest"));
      if (key === "repo") el.setAttribute("href", SITE.repo);
      if (key === "issues") el.setAttribute("href", SITE.repo + "/issues");
      if (key === "source") el.setAttribute("href", SITE.repo);
    });

    // 备案号：没拿到之前显示「办理中」，拿到后变成可点的工信部链接
    document.querySelectorAll("[data-icp]").forEach(function (el) {
      if (isTodo(SITE.icp)) {
        el.textContent = "APP 备案：办理中";
        el.removeAttribute("href");
        el.classList.add("pending");
      } else {
        el.textContent = SITE.icp;
        el.setAttribute("href", "https://beian.miit.gov.cn/");
        el.setAttribute("target", "_blank");
        el.setAttribute("rel", "noopener noreferrer");
        el.classList.remove("pending");
      }
    });

    document.querySelectorAll("[data-police]").forEach(function (el) {
      if (isTodo(SITE.police)) {
        el.hidden = true;
        el.removeAttribute("href");
      } else {
        el.textContent = SITE.police;
        el.setAttribute("href", "https://beian.mps.gov.cn/");
        el.setAttribute("target", "_blank");
        el.setAttribute("rel", "noopener noreferrer");
        el.hidden = false;
      }
    });

    document.querySelectorAll("[data-police-wrap]").forEach(function (el) {
      el.hidden = isTodo(SITE.police);
    });

    var year = document.querySelector("[data-year]");
    if (year) year.textContent = String(new Date().getFullYear());
  }

  function notice() {
    var banner = document.getElementById("config-banner");
    if (!banner) return;
    var missing = [];
    if (isTodo(SITE.operator)) missing.push("主体名称");
    if (isTodo(SITE.email)) missing.push("联系邮箱");
    if (!missing.length) {
      banner.hidden = true;
      return;
    }
    var slot = banner.querySelector("[data-missing]");
    if (slot) slot.textContent = missing.join("、");
    banner.hidden = false;
  }

  function nav() {
    var toggle = document.querySelector(".nav-toggle");
    var menu = document.getElementById("nav");
    if (!toggle || !menu) return;

    toggle.addEventListener("click", function () {
      var open = menu.classList.toggle("open");
      toggle.setAttribute("aria-expanded", open ? "true" : "false");
      toggle.textContent = open ? "关闭" : "菜单";
    });

    menu.addEventListener("click", function (e) {
      if (e.target.tagName === "A" && menu.classList.contains("open")) {
        menu.classList.remove("open");
        toggle.setAttribute("aria-expanded", "false");
        toggle.textContent = "菜单";
      }
    });
  }

  function init() {
    fill();
    notice();
    nav();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }
})();

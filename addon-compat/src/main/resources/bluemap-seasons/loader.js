(function () {
    if (window.__bluemapSeasonsLoader) return;
    window.__bluemapSeasonsLoader = true;
    const script = document.createElement('script');
    script.src = 'assets/bluemap-seasons/seasonal.js?t=' + Date.now();
    document.head.appendChild(script);
})();

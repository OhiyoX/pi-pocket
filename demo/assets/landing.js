/* Shared site behaviour: language, theme, copy buttons, image lightbox. */
(function () {
  const root = document.documentElement;
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches;

  /* ---------- language ---------- */
  const storedLang = localStorage.getItem('pocketpi.lang');
  const browserLang = (navigator.language || 'en').toLowerCase().startsWith('zh') ? 'zh' : 'en';
  let lang = storedLang === 'zh' || storedLang === 'en' ? storedLang : browserLang;

  function applyLang(next) {
    lang = next;
    root.setAttribute('data-lang', lang);
    root.setAttribute('lang', lang === 'zh' ? 'zh-CN' : 'en');
    localStorage.setItem('pocketpi.lang', lang);
    const label = document.getElementById('langToggleLabel');
    if (label) label.textContent = lang === 'zh' ? 'EN' : '中文';
    const toggle = document.getElementById('langToggle');
    if (toggle) toggle.setAttribute('aria-label', lang === 'zh' ? 'Switch to English' : '切换到中文');
    const titleEn = document.body.dataset.titleEn;
    const titleZh = document.body.dataset.titleZh;
    if (titleEn && titleZh) document.title = lang === 'zh' ? titleZh : titleEn;
  }

  applyLang(lang);

  /* ---------- scroll progress ---------- */
  const progress = document.getElementById('scrollProgress');
  function updateProgress() {
    if (!progress) return;
    const max = root.scrollHeight - root.clientHeight;
    progress.style.width = (max > 0 ? (root.scrollTop / max) * 100 : 0) + '%';
  }
  window.addEventListener('scroll', updateProgress, { passive: true });
  window.addEventListener('resize', updateProgress);
  updateProgress();
  const langToggle = document.getElementById('langToggle');
  if (langToggle) {
    langToggle.addEventListener('click', function () {
      applyLang(lang === 'zh' ? 'en' : 'zh');
    });
  }

  /* ---------- theme ---------- */
  const storedTheme = localStorage.getItem('pocketpi.theme') || 'system';
  function systemDark() {
    return window.matchMedia('(prefers-color-scheme: dark)').matches;
  }
  function applyTheme(theme) {
    const dark = theme === 'dark' || (theme === 'system' && systemDark());
    root.setAttribute('data-theme', dark ? 'dark' : 'light');
    localStorage.setItem('pocketpi.theme', theme);
    const toggle = document.getElementById('siteTheme');
    if (toggle) toggle.setAttribute('aria-label', dark ? 'Switch to light theme' : 'Switch to dark theme');
  }
  applyTheme(storedTheme);
  const themeToggle = document.getElementById('siteTheme');
  if (themeToggle) {
    themeToggle.addEventListener('click', function () {
      applyTheme(root.getAttribute('data-theme') === 'dark' ? 'light' : 'dark');
    });
  }
  window.matchMedia('(prefers-color-scheme: dark)').addEventListener('change', function () {
    if ((localStorage.getItem('pocketpi.theme') || 'system') === 'system') applyTheme('system');
  });

  /* ---------- copy buttons ---------- */
  function copyText(text) {
    if (navigator.clipboard && navigator.clipboard.writeText) return navigator.clipboard.writeText(text);
    return new Promise(function (resolve, reject) {
      const area = document.createElement('textarea');
      area.value = text;
      area.setAttribute('readonly', '');
      area.style.position = 'fixed';
      area.style.opacity = '0';
      document.body.appendChild(area);
      area.select();
      try {
        document.execCommand('copy') ? resolve() : reject(new Error('copy failed'));
      } catch (error) {
        reject(error);
      } finally {
        document.body.removeChild(area);
      }
    });
  }

  document.querySelectorAll('[data-copy]').forEach(function (button) {
    button.addEventListener('click', function () {
      const source = document.getElementById(button.dataset.copy);
      if (!source) return;
      const original = button.innerHTML;
      copyText(source.innerText).then(function () {
        button.textContent = lang === 'zh' ? '已复制' : 'Copied';
        setTimeout(function () { button.innerHTML = original; }, 1600);
      }).catch(function () {
        button.textContent = lang === 'zh' ? '请手动复制' : 'Select and copy';
        setTimeout(function () { button.innerHTML = original; }, 1800);
      });
    });
  });

  /* ---------- reveal on scroll ---------- */
  if (!reduceMotion && 'IntersectionObserver' in window) {
    const targets = document.querySelectorAll(
      '.section-head, .feature, .demo-callout, .shot, .arch, .proto-table, .security-grid, .status-card, .screen-block, .capture-note, .tour-hero'
    );
    if (targets.length) {
      root.classList.add('js');
      const observer = new IntersectionObserver(function (entries) {
        entries.forEach(function (entry) {
          if (entry.isIntersecting) {
            entry.target.classList.add('in');
            observer.unobserve(entry.target);
          }
        });
      }, { rootMargin: '0px 0px -8% 0px', threshold: 0.08 });
      targets.forEach(function (target, index) {
        target.classList.add('reveal');
        target.style.transitionDelay = (Math.min(index, 5) * 40) + 'ms';
        observer.observe(target);
      });
    }
  }

  /* ---------- lightbox ---------- */
  const lightbox = document.getElementById('lightbox');
  const lightboxImage = document.getElementById('lightboxImage');
  if (lightbox && lightboxImage) {
    document.querySelectorAll('img.zoomable').forEach(function (image) {
      image.addEventListener('click', function () {
        lightboxImage.src = image.currentSrc || image.src;
        lightboxImage.alt = image.alt || '';
        lightbox.hidden = false;
        document.getElementById('lightboxClose').focus();
      });
    });
    function closeLightbox() { lightbox.hidden = true; lightboxImage.removeAttribute('src'); }
    document.getElementById('lightboxClose').addEventListener('click', closeLightbox);
    lightbox.addEventListener('click', function (event) {
      if (event.target === lightbox) closeLightbox();
    });
    document.addEventListener('keydown', function (event) {
      if (event.key === 'Escape' && !lightbox.hidden) closeLightbox();
    });
  }
})();

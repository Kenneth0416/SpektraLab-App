(() => {
  const cfg = window.SPEKTRALAB;
  document.querySelectorAll('[data-apk]').forEach((a) => { a.href = cfg.apk; if (/\.apk$/.test(cfg.apk)) a.setAttribute('download', ''); });
  document.querySelectorAll('[data-source]').forEach((a) => { a.href = cfg.source; });
  document.querySelectorAll('[data-version]').forEach((s) => { s.textContent = cfg.version; });

  // before / after slider
  const cmp = document.getElementById('compare');
  if (cmp) cmp.querySelector('input').addEventListener('input', (e) => cmp.style.setProperty('--p', e.target.value + '%'));

  // gallery and photo credits come from one list (img/gallery.json)
  fetch('img/gallery.json').then((r) => r.json()).then((g) => {
    const host = document.getElementById('gallery');
    for (const it of g.items) {
      const f = document.createElement('figure');
      const im = document.createElement('img'); im.src = 'img/' + it.file; im.alt = it.film + ' 胶片模拟样片'; im.loading = 'lazy';
      const c = document.createElement('figcaption'); c.textContent = it.film;
      f.append(im, c); host.append(f);
    }
    if (g.compare) document.getElementById('compare-name').textContent = g.compare;
    document.getElementById('photo-credits').textContent = '样片原图：' + g.credits;
  }).catch(() => {});
})();

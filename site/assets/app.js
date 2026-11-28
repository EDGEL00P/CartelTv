(() => {
  const root = document.documentElement;
  root.classList.add('js');

  const reduced = window.matchMedia('(prefers-reduced-motion: reduce)');
  const storedMotion = localStorage.getItem('carteltv-motion');
  const motionEnabled = storedMotion ? storedMotion === 'on' : !reduced.matches;
  root.classList.toggle('motion-off', !motionEnabled);

  const motionButton = document.querySelector('[data-motion]');
  const syncMotionButton = (enabled) => {
    if (!motionButton) return;
    motionButton.setAttribute('aria-pressed', String(enabled));
    motionButton.textContent = enabled ? 'Motion on' : 'Motion off';
  };
  syncMotionButton(motionEnabled);

  if (motionButton) {
    motionButton.addEventListener('click', () => {
      const enabled = root.classList.toggle('motion-off') === false;
      localStorage.setItem('carteltv-motion', enabled ? 'on' : 'off');
      syncMotionButton(enabled);
    });
  }

  const ambient = document.querySelector('.ambient');
  if (ambient && motionEnabled && !reduced.matches) {
    let frame = 0;
    window.addEventListener('pointermove', (event) => {
      if (frame) return;
      frame = requestAnimationFrame(() => {
        const x = (event.clientX / window.innerWidth) * 100;
        const y = (event.clientY / window.innerHeight) * 100;
        ambient.style.setProperty('--pointer-x', `${x}%`);
        ambient.style.setProperty('--pointer-y', `${y}%`);
        frame = 0;
      });
    }, { passive: true });
  }

  const revealItems = document.querySelectorAll('[data-reveal]');
  if ('IntersectionObserver' in window && !reduced.matches) {
    const observer = new IntersectionObserver((entries) => {
      for (const entry of entries) {
        if (!entry.isIntersecting) continue;
        entry.target.classList.add('is-visible');
        observer.unobserve(entry.target);
      }
    }, { rootMargin: '0px 0px -8% 0px', threshold: 0.08 });
    revealItems.forEach((item) => observer.observe(item));
  } else {
    revealItems.forEach((item) => item.classList.add('is-visible'));
  }

  // Same-origin legal navigation uses the View Transitions API when available.
  document.addEventListener('click', (event) => {
    if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
    const link = event.target.closest('a');
    if (!link || link.target === '_blank' || link.hasAttribute('download')) return;
    const url = new URL(link.href, location.href);
    if (url.origin !== location.origin || url.pathname === location.pathname) return;
    event.preventDefault();
    if (document.startViewTransition && !reduced.matches) {
      try {
        const transition = document.startViewTransition(() => { location.href = url.href; });
        transition.ready.catch(() => {});
        transition.finished.catch(() => {});
      } catch {
        location.href = url.href;
      }
    } else {
      location.href = url.href;
    }
  });

  if ('serviceWorker' in navigator) {
    window.addEventListener('load', () => {
      navigator.serviceWorker.register('assets/sw.js', { scope: './' }).catch(() => {});
    });
  }
})();

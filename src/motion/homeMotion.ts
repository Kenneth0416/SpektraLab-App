import { gsap } from './gsap';

export function setupHomeMotion(root: HTMLElement) {
  const media = gsap.matchMedia();
  const motionAllowed = '(prefers-reduced-motion: no-preference)';

  media.add(motionAllowed, () => {
    document.documentElement.classList.add('js-motion');
    const intro = gsap.timeline({ defaults: { ease: 'power3.out' } });
    intro
      .from('.hero-eyebrow', { y: 16, autoAlpha: 0, duration: 0.5 })
      .from('.hero h1', { y: 28, autoAlpha: 0, duration: 0.8 }, '-=0.25')
      .from('.hero-intro', { y: 18, autoAlpha: 0, duration: 0.6 }, '-=0.45')
      .from('.hero-actions > *', { y: 16, autoAlpha: 0, duration: 0.45, stagger: 0.08 }, '-=0.35')
      .from('.hero-specs > div', { y: 12, autoAlpha: 0, duration: 0.4, stagger: 0.06 }, '-=0.25')
      .from('.hero-lab', { y: 36, autoAlpha: 0, duration: 0.9 }, 0.15);

    const underline = root.querySelector<SVGPathElement>('.memory-word path');
    if (underline) {
      const length = underline.getTotalLength();
      gsap.set(underline, { strokeDasharray: length, strokeDashoffset: length });
      intro.to(underline, { strokeDashoffset: 0, duration: 0.7, ease: 'power2.out' }, '-=0.35');
    }

    return () => document.documentElement.classList.remove('js-motion');
  }, root);

  media.add(`${motionAllowed} and (min-width: 801px)`, () => {
    const strip = root.querySelector<HTMLElement>('.filmstrip');
    const track = root.querySelector<HTMLElement>('.filmstrip-track');
    if (strip && track) {
      gsap.to(track, {
        x: () => Math.min(0, strip.clientWidth - track.scrollWidth),
        ease: 'none',
        scrollTrigger: {
          trigger: strip,
          start: 'top bottom',
          end: 'bottom top',
          scrub: 0.8,
          invalidateOnRefresh: true,
        },
      });
    }

    gsap.utils.toArray<HTMLElement>('.workflow-image img, .texture-card > img').forEach((image) => {
      gsap.fromTo(image, { scale: 1.12 }, {
        scale: 1,
        ease: 'none',
        scrollTrigger: { trigger: image.parentElement, start: 'top 90%', end: 'bottom 40%', scrub: true },
      });
    });
  }, root);

  media.add(`${motionAllowed} and (hover: hover) and (pointer: fine)`, () => {
    const removeListeners: Array<() => void> = [];
    root.querySelectorAll<HTMLElement>('.button').forEach((button) => {
      const xTo = gsap.quickTo(button, 'x', { duration: 0.35, ease: 'power3.out' });
      const yTo = gsap.quickTo(button, 'y', { duration: 0.35, ease: 'power3.out' });
      const move = (event: PointerEvent) => {
        const bounds = button.getBoundingClientRect();
        xTo((event.clientX - (bounds.left + bounds.width / 2)) * 0.25);
        yTo((event.clientY - (bounds.top + bounds.height / 2)) * 0.35);
      };
      const reset = () => { xTo(0); yTo(0); };
      button.addEventListener('pointermove', move);
      button.addEventListener('pointerleave', reset);
      removeListeners.push(() => {
        button.removeEventListener('pointermove', move);
        button.removeEventListener('pointerleave', reset);
      });
    });
    return () => removeListeners.forEach((remove) => remove());
  }, root);

  return () => media.revert();
}

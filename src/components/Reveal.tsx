import { useLayoutEffect, useRef } from 'react';
import type { ReactNode } from 'react';
import { gsap, motionReduced } from '../motion/gsap';

type RevealProps = {
  children: ReactNode;
  className?: string;
  delay?: number;
};

export function Reveal({ children, className = '', delay = 0 }: RevealProps) {
  const ref = useRef<HTMLDivElement>(null);

  useLayoutEffect(() => {
    const node = ref.current;
    if (!node || motionReduced()) return;
    const context = gsap.context(() => {
      gsap.from(node, {
        y: 32,
        autoAlpha: 0,
        duration: 0.85,
        delay: delay / 1000,
        ease: 'power3.out',
        scrollTrigger: { trigger: node, start: 'top 88%', once: true },
      });
    }, node);
    return () => context.revert();
  }, [delay]);

  return (
    <div ref={ref} className={`reveal ${className}`}>
      {children}
    </div>
  );
}

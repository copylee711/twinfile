// Progressive enhancement: static content remains readable without JavaScript.
const reduced = window.matchMedia('(prefers-reduced-motion: reduce)');
const sections = [...document.querySelectorAll('.intro, .features, .detail, .notice, .faq, .support')];
if (!reduced.matches && 'IntersectionObserver' in window) {
  const observer = new IntersectionObserver(entries => {
    for (const entry of entries) {
      if (entry.isIntersecting) {
        entry.target.classList.add('section-enter');
        observer.unobserve(entry.target);
      }
    }
  }, { threshold: 0.08 });
  sections.forEach(section => observer.observe(section));
  reduced.addEventListener('change', () => {
    if (reduced.matches) {
      observer.disconnect();
      sections.forEach(section => section.classList.remove('section-enter'));
    }
  });
}

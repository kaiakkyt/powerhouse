(() => {
	const style = document.createElement('style');
	style.textContent = `
		.bg-canvas {
			position: fixed;
			top: 0;
			left: 0;
			z-index: -1;
			pointer-events: none;
		}
		.reveal {
			opacity: 0;
			transform: translateY(20px);
			transition: opacity 0.8s ease, transform 0.8s ease;
		}
		.reveal.visible {
			opacity: 1;
			transform: translateY(0);
		}
		.status-item { transition: box-shadow 0.3s ease; }
		.status-item.glow { box-shadow: 0 0 15px rgba(0, 255, 255, 0.5); }
	`;
	document.head.appendChild(style);

	document.addEventListener('DOMContentLoaded', () => {
		const canvas = document.createElement('canvas');
		canvas.className = 'bg-canvas';
		document.body.appendChild(canvas);
		const ctx = canvas.getContext('2d');

		let DPR = window.devicePixelRatio || 1;
		function resize() {
			DPR = window.devicePixelRatio || 1;
			canvas.width = window.innerWidth * DPR;
			canvas.height = window.innerHeight * DPR;
			canvas.style.width = window.innerWidth + 'px';
			canvas.style.height = window.innerHeight + 'px';
			ctx.setTransform(DPR, 0, 0, DPR, 0, 0);
		}
		window.addEventListener('resize', resize);
		resize();

		const particles = [];
		const targetCount = Math.max(24, Math.min(80, Math.floor((window.innerWidth * window.innerHeight) / 90000)));
		for (let i = 0; i < targetCount; i++) {
			particles.push({
				x: Math.random() * window.innerWidth,
				y: Math.random() * window.innerHeight,
				r: 6 + Math.random() * 34,
				vx: (Math.random() - 0.5) * 0.35,
				vy: (Math.random() - 0.5) * 0.5,
				hue: 190 + Math.random() * 120,
				alpha: 0.06 + Math.random() * 0.16
			});
		}

		function drawParticles() {
			ctx.clearRect(0, 0, window.innerWidth, window.innerHeight);
			for (let p of particles) {
				p.x += p.vx;
				p.y += p.vy;

				if (p.x < -100) p.x = window.innerWidth + 100;
				if (p.x > window.innerWidth + 100) p.x = -100;
				if (p.y < -100) p.y = window.innerHeight + 100;
				if (p.y > window.innerHeight + 100) p.y = -100;

				const g = ctx.createRadialGradient(p.x, p.y, 0, p.x, p.y, p.r);
				g.addColorStop(0, `hsla(${p.hue},85%,65%,${p.alpha})`);
				g.addColorStop(0.5, `hsla(${p.hue},70%,50%,${p.alpha * 0.36})`);
				g.addColorStop(1, `hsla(${p.hue},60%,30%,0)`);
				
				ctx.fillStyle = g;
				ctx.beginPath();
				ctx.arc(p.x, p.y, p.r, 0, Math.PI * 2);
				ctx.fill();
			}
			requestAnimationFrame(drawParticles);
		}
		requestAnimationFrame(drawParticles);

		const observer = new IntersectionObserver((entries) => {
			entries.forEach(entry => {
				if (entry.isIntersecting) {
					entry.target.classList.add('visible');
					entry.target.querySelectorAll('[data-animate-number]').forEach(el => {
						if (el.dataset.__animated) return;
						el.dataset.__animated = '1';
						const rawText = (el.dataset.target ? String(el.dataset.target) : el.textContent || '').trim();
						const target = parseFloat(rawText.replace(/[^\d.-]/g, '')) || 0;
						const suffixMatch = (el.textContent || '').trim().match(/([^\d.,\-\s]+\s*?)$/);
						const suffix = suffixMatch ? suffixMatch[0] : '';
						const current = parseFloat((el.textContent || '').trim().replace(/[^\d.-]/g, ''));
						const start = Number.isFinite(current) ? current : Math.max(0, target - 6);
						animateNumber(el, start, target, 900 + Math.random() * 700, suffix);
					});
				}
			});
		}, { threshold: 0.12 });

		document.querySelectorAll('.card, .region-item, header, table, footer').forEach((el, i) => {
			el.classList.add('reveal');
			setTimeout(() => observer.observe(el), i * 35);
		});

		function animateNumber(el, start, end, duration, suffix) {
			const startTime = performance.now();
			function step(now) {
				const t = Math.min(1, (now - startTime) / duration);
				const eased = t < 0.5 ? 2 * t * t : -1 + (4 - 2 * t) * t;
				const value = start + (end - start) * eased;
				el.textContent = formatNumber(t === 1 ? end : value) + (suffix || '');
				if (t < 1) requestAnimationFrame(step);
			}
			requestAnimationFrame(step);
		}

		function formatNumber(n) {
			return Math.abs(n) >= 1000 ? Math.round(n).toLocaleString() : (Math.round(n * 10) / 10).toString();
		}

		document.querySelectorAll('.tps-value').forEach(el => {
			const raw = (el.textContent || '').trim();
			const parsed = parseFloat(raw.replace(/[^\d.-]/g, '')) || (Math.random() * 18 + 2);
			el.dataset.target = parsed;
			el.setAttribute('data-animate-number', '1');
			const current = parseFloat(raw.replace(/[^\d.-]/g, ''));
			const suffixMatch = raw.match(/([^\d.,\-\s]+\s*?)$/);
			const suffix = suffixMatch ? suffixMatch[0] : '';
			const start = Number.isFinite(current) ? current : Math.max(0, parsed - 6);
			setTimeout(() => animateNumber(el, start, parsed, 1200 + Math.random() * 900, suffix), 350 + Math.random() * 400);
		});

		document.querySelectorAll('.status-item').forEach(el => {
			el.addEventListener('mouseenter', () => el.classList.add('glow'));
			el.addEventListener('mouseleave', () => el.classList.remove('glow'));
		});
	});
})();

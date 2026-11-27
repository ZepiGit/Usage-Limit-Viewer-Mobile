(() => {
  "use strict";

  const GRID = 24;
  const PIVOT_X = 12;
  const PIVOT_Y = 20.4;
  const ARM = { x: 0, y: 10.95, w: 3, h: 3.1 };
  const LEG = { x: 4.487, y: 17.079, w: 1.513, h: 2.921 };
  const BODY = { x: 3, y: 5, w: 18, h: 12.079 };
  const NORMAL_EYE = { x: 6, y: 8.102, w: 1.488, h: 2.847 };
  const JOIN = 0.08;
  const FILL = "#D97757";
  const SLEEP_INK = "#B4B2A9";
  const SWEAT = "#9CC3E6";
  const SPARK = "#E6C24A";
  const AMPLITUDE = 0.7;

  function phase(elapsed, duration) {
    return (Math.max(elapsed, 0) % duration) / duration;
  }
  function clamp01(x) { return Math.min(Math.max(x, 0), 1); }

  // CSS cubic-bezier, x is time — same 24-step inverse as ClaudeCodeMotion.swift
  function eased(progress, easing) {
    const x = clamp01(progress);
    if (x === 0 || x === 1) return x;
    let x1, x2, y1;
    if (easing === "easeOut") { x1 = 0; x2 = 0.58; y1 = 0; }
    else if (easing === "easeIn") { x1 = 0.42; x2 = 1; y1 = 0; }
    else if (easing === "ease") { x1 = 0.25; x2 = 0.25; y1 = 0.1; }
    else { x1 = 0.42; x2 = 0.58; y1 = 0; } // easeInOut
    let low = 0, high = 1;
    for (let i = 0; i < 24; i++) {
      const t = (low + high) / 2;
      const inv = 1 - t;
      const value = 3 * inv * inv * t * x1 + 3 * inv * t * t * x2 + t * t * t;
      if (value < x) low = t; else high = t;
    }
    const t = (low + high) / 2;
    const inv = 1 - t;
    return 3 * inv * inv * t * y1 + 3 * inv * t * t + t * t * t;
  }

  function segment(p, start, end, from, to, easing = "easeInOut") {
    return from + (to - from) * eased((p - start) / (end - start), easing);
  }
  function pulse(p) {
    return p < 0.5 ? segment(p, 0, 0.5, 0, 1) : segment(p, 0.5, 1, 1, 0);
  }

  function pose(mood, elapsed, amplitude = AMPLITUDE, still = false) {
    const p = {
      mood, offsetY: 0, scaleY: 1, rotation: 0,
      leftArmY: 0, rightArmY: 0, evenLegY: 0, oddLegY: 0,
      eyeScaleY: 1, eyeOffsetX: 0,
    };
    if (still) {
      if (mood === "hooray") {
        p.leftArmY = -ARM.h * 1.5;
        p.rightArmY = p.leftArmY;
      }
      return p;
    }
    if (mood === "asleep" || mood === "tired" || mood === "idle") {
      const duration = mood === "asleep" ? 4.4 : mood === "tired" ? 2.2 : 3.2;
      p.scaleY = 1 - 0.045 * amplitude * pulse(phase(elapsed, duration));
    } else if (mood === "working") {
      p.offsetY = -GRID * 0.03 * amplitude * pulse(phase(elapsed, 0.44));
      p.leftArmY = -ARM.h * 0.35 * pulse(phase(elapsed, 0.24));
      p.rightArmY = -ARM.h * 0.35 * pulse(phase(elapsed + 0.12, 0.24));
    } else if (mood === "dancing") {
      const hop = phase(elapsed, 0.5);
      if (hop < 0.12) {
        p.scaleY = segment(hop, 0, 0.12, 1, 1 - 0.09 * amplitude);
      } else if (hop < 0.45) {
        p.scaleY = segment(hop, 0.12, 0.45, 1 - 0.09 * amplitude, 1);
        p.offsetY = segment(hop, 0.12, 0.45, 0, -GRID * 0.13 * amplitude);
      } else if (hop < 0.78) {
        p.offsetY = segment(hop, 0.45, 0.78, -GRID * 0.13 * amplitude, 0);
      }
      p.rotation = -6 * amplitude + 12 * amplitude * pulse(phase(elapsed, 1));
      const left = pulse(hop);
      const right = pulse(phase(elapsed + 0.25, 0.5));
      p.leftArmY = -ARM.h * 1.5 * left;
      p.rightArmY = -ARM.h * 1.5 * right;
      p.evenLegY = -LEG.h * 0.4 * left;
      p.oddLegY = -LEG.h * 0.4 * right;
    } else if (mood === "hooray") {
      p.leftArmY = -ARM.h * 1.5 * eased(Math.min(Math.max(elapsed, 0) / 0.3, 1), "easeOut");
      p.rightArmY = p.leftArmY;
      const jump = phase(elapsed, 0.8);
      if (jump < 0.35) {
        p.offsetY = segment(jump, 0, 0.35, 0, -GRID * 0.16 * amplitude, "easeOut");
      } else if (jump < 0.65) {
        p.offsetY = segment(jump, 0.35, 0.65, -GRID * 0.16 * amplitude, 0, "easeOut");
      }
    }
    if (mood === "idle" || mood === "working" || mood === "dancing" || mood === "hooray") {
      const duration = mood === "idle" ? 4.6 : mood === "working" ? 3.4 : 2.6;
      const blink = phase(elapsed, duration);
      if (blink >= 0.93 && blink < 0.96) p.eyeScaleY = segment(blink, 0.93, 0.96, 1, 0.1, "ease");
      else if (blink >= 0.96) p.eyeScaleY = segment(blink, 0.96, 1, 0.1, 1, "ease");
    }
    if (mood === "idle") {
      const look = phase(elapsed, 7.5);
      const distance = NORMAL_EYE.w * 0.45;
      if (look >= 0.28 && look < 0.36) p.eyeOffsetX = segment(look, 0.28, 0.36, 0, -distance);
      else if (look >= 0.36 && look < 0.58) p.eyeOffsetX = -distance;
      else if (look >= 0.58 && look < 0.66) p.eyeOffsetX = segment(look, 0.58, 0.66, -distance, distance);
      else if (look >= 0.66 && look < 0.88) p.eyeOffsetX = distance;
      else if (look >= 0.88) p.eyeOffsetX = segment(look, 0.88, 1, distance, 0);
    }
    return p;
  }

  function eyeRect(p, index) {
    let base;
    if (p.mood === "asleep") {
      base = { x: index === 0 ? 5.6 : 16.2, y: 9.9, w: 2.2, h: 0.6 };
    } else if (p.mood === "tired") {
      base = { x: index === 0 ? 6 : 16.51, y: 9.6, w: 1.488, h: 1.35 };
    } else {
      base = {
        x: index === 0 ? NORMAL_EYE.x : 16.51,
        y: NORMAL_EYE.y + (p.mood === "working" ? NORMAL_EYE.h * 0.25 : 0),
        w: NORMAL_EYE.w, h: NORMAL_EYE.h,
      };
    }
    return {
      x: base.x + p.eyeOffsetX,
      y: base.y + base.h * (1 - p.eyeScaleY) / 2,
      w: base.w,
      h: base.h * p.eyeScaleY,
    };
  }

  function particleCount(mood) {
    if (mood === "asleep" || mood === "hooray") return 3;
    if (mood === "tired") return 1;
    if (mood === "dancing") return 2;
    return 0;
  }

  function particle(mood, index, elapsed, still) {
    if (mood === "asleep") {
      const part = { kind: "sleep", x: GRID * (0.64 + index * 0.09), y: GRID * (0.06 - index * 0.06),
        w: GRID * (0.16 + index * 0.05), h: GRID * (0.16 + index * 0.05), opacity: 1, scale: 1, tx: 0, ty: 0 };
      if (still) { part.opacity = index === 0 ? 1 : 0; return part; }
      const p = phase(elapsed + index * 0.9, 2.7);
      part.opacity = p < 0.2 ? segment(p, 0, 0.2, 0, 1, "easeOut") : segment(p, 0.2, 1, 1, 0, "easeOut");
      const travel = eased(p, "easeOut");
      part.scale = 0.6 + 0.4 * travel;
      part.tx = 0.7 * travel;
      part.ty = -1.7 * travel;
      return part;
    }
    if (mood === "tired") {
      const part = { kind: "sweat", x: GRID * 0.8, y: GRID * 0.04, w: GRID * 0.09, h: GRID * 0.13,
        opacity: 1, scale: 1, tx: 0, ty: 0 };
      if (still) return part;
      const p = phase(elapsed, 1.8);
      part.opacity = p < 0.15 ? segment(p, 0, 0.15, 0, 1, "easeIn") : p <= 0.75 ? 1 : segment(p, 0.75, 1, 1, 0, "easeIn");
      part.ty = 1.4 * eased(p, "easeIn");
      return part;
    }
    if (mood === "dancing") {
      const part = { kind: "note", x: GRID * (index === 0 ? -0.08 : 0.92), y: GRID * (index === 0 ? 0.02 : -0.04),
        w: GRID * 0.24, h: GRID * 0.24, opacity: 1, scale: 1, tx: 0, ty: 0 };
      if (still) return part;
      const p = phase(elapsed + index * 0.6, 1.2);
      part.opacity = p < 0.3 ? segment(p, 0, 0.3, 0, 1, "easeOut") : segment(p, 0.3, 1, 1, 0, "easeOut");
      part.ty = 0.2 - 1.5 * eased(p, "easeOut");
      return part;
    }
    if (mood === "hooray") {
      const part = { kind: "sparkle",
        x: GRID * (index === 0 ? 0.02 : index === 1 ? 0.86 : 0.46),
        y: GRID * (index === 0 ? 0 : index === 1 ? 0.06 : -0.14),
        w: GRID * 0.07, h: GRID * 0.07, opacity: 1, scale: 1, tx: 0, ty: 0 };
      if (still) return part;
      const twinkle = pulse(phase(elapsed + index * 0.3, 0.9));
      part.opacity = twinkle;
      part.scale = 0.3 + 0.7 * twinkle;
      return part;
    }
    return null;
  }

  // Moods from remaining % / exhausted, matching claudeCodeMood().
  function moodFromUsage(remainingPercent, exhausted) {
    if (exhausted) return "asleep";
    if (remainingPercent == null || Number.isNaN(remainingPercent)) return "idle";
    if (remainingPercent <= 0) return "asleep";
    if (remainingPercent <= 20) return "tired";
    if (remainingPercent <= 50) return "working";
    if (remainingPercent <= 90) return "idle";
    return "dancing";
  }

  function svgFor(mood, id) {
    const uid = `cf-${id}`;
    const legX = [4.487, 7.488, 15, 18];
    const legs = legX.map((x, i) =>
      `<rect class="leg" data-i="${i}" x="${x}" y="${LEG.y - JOIN}" width="${LEG.w}" height="${LEG.h + JOIN}" fill="${FILL}"/>`
    ).join("");
    return `<svg class="claude-svg" viewBox="-6 -8 36 36" aria-hidden="true" focusable="false">
      <defs>
        <mask id="${uid}-mask">
          <rect x="-6" y="-8" width="36" height="36" fill="white"/>
          <rect class="eye" data-i="0" fill="black"/>
          <rect class="eye" data-i="1" fill="black"/>
        </mask>
      </defs>
      <g class="particles"></g>
      <g class="xform" mask="url(#${uid}-mask)">
        <rect class="body" x="${BODY.x}" y="${BODY.y}" width="${BODY.w}" height="${BODY.h}" fill="${FILL}"/>
        <rect class="arm-l" x="0" y="${ARM.y}" width="${ARM.w + JOIN}" height="${ARM.h}" fill="${FILL}"/>
        <rect class="arm-r" x="${21 - JOIN}" y="${ARM.y}" width="${ARM.w + JOIN}" height="${ARM.h}" fill="${FILL}"/>
        ${legs}
      </g>
    </svg>`;
  }

  function applyPose(root, mood, elapsed, still) {
    const p = pose(mood, elapsed, AMPLITUDE, still);
    const xform = root.querySelector(".xform");
    xform.setAttribute(
      "transform",
      `translate(${PIVOT_X} ${PIVOT_Y}) rotate(${p.rotation}) translate(0 ${p.offsetY}) scale(1 ${p.scaleY}) translate(${-PIVOT_X} ${-PIVOT_Y})`
    );
    const armL = root.querySelector(".arm-l");
    const armR = root.querySelector(".arm-r");
    armL.setAttribute("y", String(ARM.y + p.leftArmY));
    armR.setAttribute("y", String(ARM.y + p.rightArmY));
    root.querySelectorAll(".leg").forEach((el) => {
      const i = Number(el.dataset.i);
      const dy = (i % 2 === 0) ? p.evenLegY : p.oddLegY;
      el.setAttribute("y", String(LEG.y - JOIN + dy));
    });
    root.querySelectorAll(".eye").forEach((el) => {
      const e = eyeRect(p, Number(el.dataset.i));
      el.setAttribute("x", e.x);
      el.setAttribute("y", e.y);
      el.setAttribute("width", e.w);
      el.setAttribute("height", Math.max(0.01, e.h));
    });
    const layer = root.querySelector(".particles");
    const n = particleCount(mood);
    // Rebuild particles when the mood's count changes.
    if (layer.childElementCount !== n) {
      layer.replaceChildren();
      for (let i = 0; i < n; i++) {
        const g = document.createElementNS("http://www.w3.org/2000/svg", "g");
        g.classList.add("particle");
        layer.appendChild(g);
      }
    }
    layer.querySelectorAll(".particle").forEach((g, i) => {
      const part = particle(mood, i, elapsed, still);
      if (!part) { g.replaceChildren(); return; }
      if (g.dataset.kind !== part.kind) {
        g.dataset.kind = part.kind;
        if (part.kind === "sleep") {
          g.innerHTML = `<text text-anchor="middle" dominant-baseline="central" font-family="ui-monospace, SFMono-Regular, Menlo, monospace" font-weight="700" fill="${SLEEP_INK}">z</text>`;
        } else if (part.kind === "sweat") {
          g.innerHTML = `<rect width="${part.w}" height="${part.h}" rx="${part.w * 0.5}" ry="${part.h * 0.62}" fill="${SWEAT}"/>`;
        } else if (part.kind === "note") {
          g.innerHTML = `<text text-anchor="middle" dominant-baseline="central" font-size="${part.w}" fill="${FILL}">♪</text>`;
        } else {
          g.innerHTML = `<rect width="${part.w}" height="${part.h}" fill="${SPARK}"/>`;
        }
      }
      if (part.kind === "sleep") {
        const text = g.querySelector("text");
        text.setAttribute("font-size", String(part.w));
      }
      const ox = part.kind === "sleep" || part.kind === "note" ? 0 : 0;
      const wordTx = part.tx * part.w;
      const wordTy = part.ty * part.h;
      g.setAttribute("transform", `translate(${part.x + wordTx} ${part.y + wordTy}) scale(${part.scale})`);
      g.setAttribute("opacity", String(part.opacity));
    });
  }

  window.ClaudeMotion = {
    pose, particle, particleCount, moodFromUsage, svgFor, applyPose,
    AMPLITUDE, FILL,
  };
})();

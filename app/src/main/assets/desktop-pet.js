// Mochi, a small fox that naps between walks.
const ctx = canvas.getContext("2d");
const reducedMotion = matchMedia("(prefers-reduced-motion: reduce)").matches;
let lastFrame = -Infinity;

function oval(x, y, width, height, color) {
    ctx.fillStyle = color;
    ctx.beginPath();
    ctx.ellipse(x, y, width, height, 0, 0, Math.PI * 2);
    ctx.fill();
}

function triangle(ax, ay, bx, by, cx, cy, color) {
    ctx.fillStyle = color;
    ctx.beginPath();
    ctx.moveTo(ax, ay);
    ctx.lineTo(bx, by);
    ctx.lineTo(cx, cy);
    ctx.closePath();
    ctx.fill();
}

function draw(now) {
    if (!reducedMotion) requestAnimationFrame(draw);
    if (now - lastFrame < 1000 / 30) return;
    lastFrame = now;
    const time = now / 1000;
    const cycle = time % 14;
    const walking = !reducedMotion && cycle < 8;
    const travel = Math.min(cycle, 8) / 8;
    const x = reducedMotion ? 120 : 120 - Math.cos(travel * Math.PI * 2) * 47;
    const bob = walking ? Math.abs(Math.sin(time * 13)) * 6 : Math.sin(time * 2) * 2;
    const asleep = !walking && !reducedMotion;

    ctx.clearRect(0, 0, 240, 240);
    oval(x, 166, 38, 9, "rgba(22,10,29,.32)");
    ctx.save();
    ctx.translate(x, 130 - bob);
    ctx.scale(cycle < 4 || reducedMotion ? 1 : -1, 1);
    ctx.save();
    ctx.translate(-18, 16);
    ctx.rotate(-0.23 + Math.sin(time * (walking ? 9 : 2)) * 0.16);
    oval(-19, -3, 26, 13, "#da653b");
    oval(-34, -5, 11, 8, "#ffe7c6");
    ctx.restore();
    oval(-7, 19, 31, 18, "#da653b");
    for (const side of [-1, 1]) {
        const stride = walking ? Math.sin(time * 13) * side * 5 : 0;
        oval(-5 + side * 17 + stride, 33, 9, 5, "#69394b");
    }
    triangle(-18, -5, -11, -37, 8, -12, "#ee8951");
    triangle(18, -12, 35, -37, 39, -5, "#ee8951");
    triangle(-12, -10, -8, -27, 1, -11, "#a64b5c");
    triangle(25, -10, 33, -27, 34, -10, "#a64b5c");
    oval(11, 4, 31, 23, "#ee8951");
    oval(18, 13, 24, 12, "#ffe7c6");
    const eyes = asleep || (!reducedMotion && time % 3.7 < 0.13);
    oval(4, 4, 3, eyes ? 1 : 4, "#442b46");
    oval(26, 4, 3, eyes ? 1 : 4, "#442b46");
    oval(17, 12, 5, 3, "#442b46");
    ctx.restore();
    if (asleep) {
        ctx.font = "bold 16px sans-serif";
        ctx.fillStyle = "#442b46";
        ctx.fillText("z", x + 28, 91 + Math.sin(time * 3) * 4);
        ctx.fillText("z", x + 40, 78 + Math.sin(time * 3) * 4);
    }
}

draw(0);

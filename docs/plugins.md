# JavaScript plugins for Expressive Launcher

Open **Launcher Settings → Plugins → Import JavaScript file** and select a UTF-8 `.js` file up to 64 KB. You can keep up to three plugins and enable, disable, or remove each one separately. Enabled plugins appear in a column on the home screen.

## Write a plugin

The launcher provides a transparent 240 × 240 HTML canvas named `canvas`. Use the Canvas 2D API to draw and `requestAnimationFrame` to animate. The origin is the top-left corner. The script starts when its panel first appears and keeps its in-memory state while the app drawer or settings are open.

Enable **Allow taps** for a plugin to receive `launchertap` events on the canvas. The event carries `detail.x` and `detail.y` in canvas coordinates. When taps are disabled, the area passes input through to the home screen.

The canvas emits `launchervisibilitychange` with `detail.visible` when the panel is shown or hidden. If your plugin uses timers, pause them when the panel is hidden.

This example shows a counter that increases on each tap. Save it as `counter.js`, import it, and enable **Allow taps**.

```js
const ctx = canvas.getContext("2d");
let count = 0;

function draw() {
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.fillStyle = "#45316f";
    ctx.beginPath();
    ctx.arc(120, 120, 84, 0, 2 * Math.PI);
    ctx.fill();
    ctx.fillStyle = "#ffffff";
    ctx.font = "bold 60px sans-serif";
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    ctx.fillText(String(count), 120, 120);
}

canvas.addEventListener("launchertap", () => {
    count++;
    draw();
});

draw();
```

Plugins run locally in separate WebViews. The launcher blocks network, file, and content-provider access and exposes no native launcher or Android APIs to scripts.

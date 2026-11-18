# JavaScript plugins for Expressive Launcher

Open **Launcher Settings → Plugins** and select **Add Mochi the fox**. Return to the home screen to see Mochi walk, blink, and nap.

To use your own plugin, choose **Import JavaScript file** and select a UTF-8 `.js` file up to 64 KB. The launcher supports one active desktop plugin. Importing a file replaces the current plugin. Select **Remove active plugin** to clear it.

## Write a plugin

Your script runs in a local WebView with a transparent 240 × 240 HTML canvas available as `canvas`. You can draw with the Canvas 2D API and animate with `requestAnimationFrame`. Coordinates start at the top left. The launcher scales the canvas to fit its desktop area and leaves touch input available to the home screen.

```js
const ctx = canvas.getContext("2d");
const reducedMotion = matchMedia("(prefers-reduced-motion: reduce)").matches;

function draw(time) {
    const y = reducedMotion ? 120 : 120 + Math.sin(time / 450) * 16;
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.beginPath();
    ctx.arc(120, y, 30, 0, 2 * Math.PI);
    ctx.fillStyle = "#e98450";
    ctx.fill();
    if (!reducedMotion) requestAnimationFrame(draw);
}

draw(0);
```

Save the script with a `.js` extension and import it. The built-in [Mochi plugin](../app/src/main/assets/desktop-pet.js) is a complete example.

The renderer blocks network, file, and content-provider access. It exposes no native launcher APIs or Android permissions to scripts. The plugin is recreated when you return to the unobstructed home screen, so initialize its visual state at the start of the script.

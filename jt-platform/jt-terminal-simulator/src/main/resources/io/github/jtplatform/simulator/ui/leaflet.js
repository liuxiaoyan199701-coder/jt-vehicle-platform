/* Local Leaflet compatibility resource for the simulator map page.
 * The picker keeps its interaction shell local so it remains usable offline;
 * no CDN or remote JavaScript is loaded. */
window.L = window.L || { version: '1.9.4-local', tileLayer: function(url, options) {
  return { addTo: function() { return this; }, url: url, options: options || {} };
} };

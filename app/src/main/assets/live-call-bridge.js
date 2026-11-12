(function () {
  'use strict';
  if (!window.APSNativeLiveMessages) return;
  if (window.APSLiveCalls && window.APSLiveCalls.bridgeVersion === 2) {
    window.dispatchEvent(new CustomEvent('aps-live-bridge-ready'));
    return;
  }
  var pending = Object.create(null), sequence = 0;
  APSNativeLiveMessages.onmessage = function (event) {
    var reply;
    try { reply = JSON.parse(event.data); } catch (_) { return; }
    if (!reply || typeof reply.id !== 'string' || !pending[reply.id]) return;
    var request = pending[reply.id];
    delete pending[reply.id]; clearTimeout(request.timer);
    request.resolve(reply.value);
  };
  function request(action, fallback) {
    return new Promise(function (resolve) {
      var id = 'aps-' + Date.now().toString(36) + '-' + (++sequence).toString(36);
      var timer = setTimeout(function () { if (!pending[id]) return; delete pending[id]; resolve(fallback); }, action === 'prepareLogout' ? 2500 : 5000);
      pending[id] = { resolve: resolve, timer: timer };
      try { APSNativeLiveMessages.postMessage(JSON.stringify({ id: id, action: action })); }
      catch (_) { clearTimeout(timer); delete pending[id]; resolve(fallback); }
    });
  }
  window.APSLiveCalls = {
    bridgeVersion: 2,
    prepareLogout: function () { return request('prepareLogout', false).then(function (value) { return value === true; }); },
    openSetup: function () { return request('openSetup', false).then(function (value) { return value === true; }); },
    getSetupStatus: function () { return request('getSetupStatus', { supported: false }).then(function (value) { return value && typeof value === 'object' ? value : { supported: false }; }); }
  };
  window.dispatchEvent(new CustomEvent('aps-live-bridge-ready'));
})();

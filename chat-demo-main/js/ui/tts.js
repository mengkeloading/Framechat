(function (global) {
  'use strict';
  var synth = global.speechSynthesis;
  var Utter = global.SpeechSynthesisUtterance;
  var busy = false;

  function available() { return !!(synth && Utter); }

  function speak(text) {
    if (!available()) return false;
    try {
      synth.cancel();
      var u = new Utter(String(text || ''));
      u.lang = 'zh-CN';
      u.rate = 1;
      u.onend = function () { busy = false; };
      u.onerror = function () { busy = false; };
      busy = true;
      synth.speak(u);
      return true;
    } catch (e) {
      busy = false;
      return false;
    }
  }

  function stop() {
    if (!available()) return;
    try { synth.cancel(); } catch (e) {}
    busy = false;
  }

  function speaking() { return busy; }

  global.Tts = {
    available: available, speak: speak,
    stop: stop, speaking: speaking
  };
})(window);

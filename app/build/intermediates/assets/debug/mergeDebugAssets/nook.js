(function () {
  if (window.__nookLoaded) return;
  window.__nookLoaded = true;

  var ready = false;
  var selTimer = null;
  var scrollTimer = null;

  function textNodes() {
    var out = [];
    if (!document.body) return out;
    var w = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
      acceptNode: function (n) {
        var p = n.parentNode && n.parentNode.nodeName;
        if (p === 'SCRIPT' || p === 'STYLE' || p === 'NOSCRIPT') return NodeFilter.FILTER_REJECT;
        return NodeFilter.FILTER_ACCEPT;
      }
    });
    var n;
    while ((n = w.nextNode())) out.push(n);
    return out;
  }

  function absOffset(container, offset) {
    var nodes = textNodes();
    var pos = 0;
    for (var i = 0; i < nodes.length; i++) {
      if (nodes[i] === container) return pos + offset;
      pos += nodes[i].nodeValue.length;
    }
    if (container.nodeType === 1) {
      var r = document.createRange();
      r.setStart(document.body, 0);
      r.setEnd(container, offset);
      return r.toString().length;
    }
    return pos;
  }

  function css(c) {
    var t = c.theme;
    var s = '';
    s += 'html,body{background:' + t.bg + '!important;color:' + t.fg + '!important;}';
    s += 'body{margin:0 auto!important;padding:28px ' + c.margin + '%!important;max-width:' + c.maxWidth +
      'px!important;box-sizing:border-box!important;font-size:' + c.fontSize + 'px!important;line-height:' +
      c.lineHeight + '!important;';
    if (c.fontFamily) s += 'font-family:' + c.fontFamily + '!important;';
    if (c.justify) s += 'text-align:justify!important;-webkit-hyphens:auto!important;hyphens:auto!important;';
    s += '}';
    s += 'body *{color:inherit!important;background-color:transparent!important;}';
    s += 'a,a *{color:' + t.link + '!important;}';
    s += 'body p,body li,body blockquote,body td,body dd,body dt{font-size:1em!important;line-height:inherit!important;';
    if (c.fontFamily) s += 'font-family:inherit!important;';
    s += '}';
    s += 'img,svg,video{max-width:100%!important;height:auto!important;}';
    s += 'mark.nook-hl{color:#111!important;border-radius:2px;}';
    s += '::selection{background:rgba(80,140,255,0.35)!important;}';
    s += '*{-webkit-tap-highlight-color:transparent;}';
    return s;
  }

  function fractionNow() {
    var h = document.documentElement.scrollHeight - window.innerHeight;
    return h > 0 ? window.scrollY / h : 0;
  }

  function scrollToFraction(f) {
    var h = document.documentElement.scrollHeight - window.innerHeight;
    window.scrollTo(0, h > 0 ? h * f : 0);
  }

  function applyStyle(c) {
    var el = document.getElementById('nook-style');
    if (!el) {
      el = document.createElement('style');
      el.id = 'nook-style';
      (document.head || document.documentElement).appendChild(el);
    }
    el.textContent = css(c);
  }

  function wrapRange(s, e, id, color) {
    var nodes = textNodes();
    var pos = 0;
    for (var i = 0; i < nodes.length; i++) {
      var n = nodes[i];
      var len = n.nodeValue.length;
      var ns = pos, ne = pos + len;
      pos = ne;
      if (ne <= s) continue;
      if (ns >= e) break;
      var a = Math.max(s, ns) - ns;
      var b = Math.min(e, ne) - ns;
      var node = n;
      if (b < len) node.splitText(b);
      if (a > 0) node = node.splitText(a);
      var m = document.createElement('mark');
      m.className = 'nook-hl';
      m.setAttribute('data-id', id);
      m.style.setProperty('background-color', color, 'important');
      node.parentNode.insertBefore(m, node);
      m.appendChild(node);
    }
  }

  function removeMarks(id) {
    var list = document.querySelectorAll('mark.nook-hl[data-id="' + id + '"]');
    for (var i = 0; i < list.length; i++) {
      var m = list[i];
      var p = m.parentNode;
      while (m.firstChild) p.insertBefore(m.firstChild, m);
      p.removeChild(m);
      p.normalize();
    }
  }

  window.nookAddHighlight = function (h) {
    wrapRange(h.start, h.end, h.id, h.color);
    var s = window.getSelection();
    if (s) s.removeAllRanges();
  };

  window.nookRemoveHighlight = function (id) {
    removeMarks(id);
  };

  window.nookRecolor = function (id, color) {
    var list = document.querySelectorAll('mark.nook-hl[data-id="' + id + '"]');
    for (var i = 0; i < list.length; i++) list[i].style.setProperty('background-color', color, 'important');
  };

  window.nookApplyStyle = function (c) {
    var f = fractionNow();
    applyStyle(c);
    if (ready) scrollToFraction(f);
  };

  window.nookInit = function (c, fraction, highlights, anchor, find) {
    ready = false;
    applyStyle(c);
    var old = document.querySelectorAll('mark.nook-hl');
    for (var k = 0; k < old.length; k++) {
      var om = old[k];
      var op = om.parentNode;
      while (om.firstChild) op.insertBefore(om.firstChild, om);
      op.removeChild(om);
      op.normalize();
    }
    for (var i = 0; i < highlights.length; i++) {
      var h = highlights[i];
      wrapRange(h.start, h.end, h.id, h.color);
    }
    function place() {
      if (anchor) {
        var el = null;
        if (anchor.indexOf('hl:') === 0) {
          el = document.querySelector('mark.nook-hl[data-id="' + anchor.substring(3) + '"]');
        } else {
          el = document.getElementById(anchor) || document.getElementsByName(anchor)[0];
        }
        if (el) {
          el.scrollIntoView();
          return;
        }
      }
      scrollToFraction(fraction);
    }
    place();
    setTimeout(function () {
      place();
      if (find) window.find(find);
      ready = true;
      if (window.Nook) Nook.onReady();
    }, 450);
  };

  window.nookPage = function (dir) {
    var max = document.documentElement.scrollHeight - window.innerHeight;
    if (dir > 0 && window.scrollY >= max - 2) {
      Nook.onEdge(1);
      return;
    }
    if (dir < 0 && window.scrollY <= 2) {
      Nook.onEdge(-1);
      return;
    }
    window.scrollBy({ top: dir * window.innerHeight * 0.9, behavior: 'smooth' });
  };

  window.nookScrollToFraction = function (f) {
    scrollToFraction(f);
  };

  function reportSelection() {
    var sel = window.getSelection();
    var text = sel ? String(sel) : '';
    if (!sel || sel.rangeCount === 0 || text.trim().length === 0) {
      Nook.onSelection('');
      return;
    }
    var r = sel.getRangeAt(0);
    var start = absOffset(r.startContainer, r.startOffset);
    var end = absOffset(r.endContainer, r.endOffset);
    var node = r.commonAncestorContainer;
    if (node.nodeType === 3) node = node.parentNode;
    var block = node;
    while (block && block !== document.body && !/^(P|DIV|LI|BLOCKQUOTE|SECTION|ARTICLE|TD|H[1-6])$/.test(block.nodeName)) {
      block = block.parentNode;
    }
    var ctx = (block && block.textContent ? block.textContent : text).replace(/\s+/g, ' ').trim();
    if (ctx.length > 800) {
      var i = ctx.indexOf(text.substring(0, 30).replace(/\s+/g, ' '));
      if (i < 0) i = 0;
      var a = Math.max(0, i - 300);
      ctx = ctx.substring(a, a + 800);
    }
    Nook.onSelection(JSON.stringify({ text: text, start: start, end: end, context: ctx }));
  }

  document.addEventListener('selectionchange', function () {
    if (selTimer) clearTimeout(selTimer);
    selTimer = setTimeout(reportSelection, 250);
  });

  document.addEventListener('click', function (e) {
    var sel = window.getSelection();
    if (sel && String(sel).length > 0) return;
    var t = e.target;
    var mk = t && t.closest ? t.closest('mark.nook-hl') : null;
    if (mk) {
      Nook.onHighlightTap(mk.getAttribute('data-id'));
      return;
    }
    var a = t && t.closest ? t.closest('a[href]') : null;
    if (a) return;
    Nook.onTap(e.clientX / window.innerWidth);
  }, true);

  window.addEventListener('scroll', function () {
    if (!ready || scrollTimer) return;
    scrollTimer = setTimeout(function () {
      scrollTimer = null;
      Nook.onScroll(fractionNow());
    }, 300);
  }, { passive: true });
})();

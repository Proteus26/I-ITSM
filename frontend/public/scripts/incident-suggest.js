// Progressive enhancement for /incidents/new: after a pause in typing, asks the same-origin proxy for a category
// suggestion and for similar incidents. The form works without this script. All failures are ignored.
// Everything shown is built with textContent (never innerHTML), because the texts come from the backend.
(function () {
  var title = document.getElementById('f-title');
  var description = document.getElementById('f-description');
  var category = document.getElementById('f-categoryCode');
  var catBox = document.getElementById('suggest-category');
  var simBox = document.getElementById('suggest-similar');
  if (!title || !description || !category || !catBox || !simBox || typeof fetch !== 'function' || typeof AbortController !== 'function') return;

  var DELAY_MS = 700;
  var MIN_TITLE = 4;
  var MAX_TITLE = 200;
  var MAX_QUERY_TEXT = 500; // the backend only looks at the start of the description
  var timer = null;
  var controller = null;
  var lastKey = '';

  function clear(el) {
    while (el.firstChild) el.removeChild(el.firstChild);
    el.hidden = true;
  }

  function hasOption(code) {
    for (var i = 0; i < category.options.length; i++) if (category.options[i].value === code) return true;
    return false;
  }

  function showCategory(s) {
    clear(catBox);
    if (!s || typeof s.code !== 'string' || !hasOption(s.code) || category.value === s.code) return;
    var label = category.options[Array.prototype.findIndex.call(category.options, function (o) { return o.value === s.code; })].textContent;
    var p = document.createElement('p');
    p.textContent = 'Suggested category: ' + label + (s.reason ? ' (' + s.reason + ')' : '');
    var btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'btn btn-secondary btn-small';
    btn.textContent = 'Use it';
    btn.addEventListener('click', function () {
      category.value = s.code;
      clear(catBox);
    });
    catBox.appendChild(p);
    catBox.appendChild(btn);
    catBox.hidden = false;
  }

  function showSimilar(list) {
    clear(simBox);
    if (!Array.isArray(list) || list.length === 0) return;
    var p = document.createElement('p');
    p.textContent = 'Possible duplicates: check whether your problem is already reported.';
    var ul = document.createElement('ul');
    list.slice(0, 5).forEach(function (i) {
      if (!i || !Number.isInteger(i.id)) return;
      var li = document.createElement('li');
      var a = document.createElement('a');
      a.href = '/incidents/' + i.id;
      a.target = '_blank';
      a.rel = 'noopener';
      a.textContent = String(i.number) + ': ' + String(i.title);
      li.appendChild(a);
      ul.appendChild(li);
    });
    if (!ul.firstChild) return;
    simBox.appendChild(p);
    simBox.appendChild(ul);
    simBox.hidden = false;
  }

  async function get(path, params, signal) {
    var res = await fetch(path + '?' + params.toString(), {
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
      signal: signal,
    });
    if (!res.ok || res.status === 204) return null;
    return res.json();
  }

  function run() {
    var t = title.value.trim();
    var d = description.value.trim().slice(0, MAX_QUERY_TEXT);
    if (t.length < MIN_TITLE || t.length > MAX_TITLE) {
      // Forget the last query and cancel the request in flight, so suggestions can reappear
      // after retyping and a late response cannot refill the cleared boxes.
      lastKey = '';
      if (controller) controller.abort();
      controller = null;
      clear(catBox);
      clear(simBox);
      return;
    }
    var key = t + '\n' + d;
    if (key === lastKey) return;
    lastKey = key;
    if (controller) controller.abort();
    controller = new AbortController();
    var signal = controller.signal;
    var params = new URLSearchParams({ title: t, description: d });
    get('/api/intelligence/suggest-category', params, signal).then(showCategory, function () {});
    get('/api/intelligence/similar', params, signal).then(showSimilar, function () {});
  }

  function schedule() {
    clearTimeout(timer);
    timer = setTimeout(run, DELAY_MS);
  }

  title.addEventListener('input', schedule);
  description.addEventListener('input', schedule);
})();

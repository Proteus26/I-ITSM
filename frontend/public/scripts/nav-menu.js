// Progressive enhancement for the Admin menu (<details>): close it on Escape and on a click outside.
// Without this script the menu still opens and closes with its summary.
(function () {
  var menus = document.querySelectorAll('.admin-menu details');
  if (!menus.length) return;
  document.addEventListener('click', function (e) {
    menus.forEach(function (d) {
      if (d.open && !d.contains(e.target)) d.open = false;
    });
  });
  document.addEventListener('keydown', function (e) {
    if (e.key !== 'Escape') return;
    menus.forEach(function (d) {
      if (!d.open) return;
      d.open = false;
      var s = d.querySelector('summary');
      if (s && d.contains(document.activeElement)) s.focus();
    });
  });
})();

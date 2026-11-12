// Asks before submitting a form that has data-confirm="message". Without JavaScript the form just submits.
document.addEventListener('submit', function (event) {
  var form = event.target;
  var message = form && form.getAttribute && form.getAttribute('data-confirm');
  if (message && !window.confirm(message)) event.preventDefault();
});

const filterButtons = Array.from(document.querySelectorAll(".filter-button"));
const tableCards = Array.from(document.querySelectorAll(".table-card"));

function applyFilter(kind) {
  tableCards.forEach((card) => {
    const visible = kind === "all" || card.dataset.kind === kind;
    card.classList.toggle("is-hidden", !visible);
  });
}

filterButtons.forEach((button) => {
  button.addEventListener("click", () => {
    filterButtons.forEach((item) => item.classList.remove("is-active"));
    button.classList.add("is-active");
    applyFilter(button.dataset.filter || "all");
  });
});

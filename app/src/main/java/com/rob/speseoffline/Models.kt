package com.rob.speseoffline

enum class TxType { EXPENSE, INCOME }

data class Category(
    val id: Long,
    val name: String,
    val icon: String = "•",
    val colorArgb: Long = 0xFF5B6CFF
)

data class Transaction(
    val id: Long,
    val amountCents: Long,
    val categoryId: Long,
    val categoryName: String,
    val categoryIcon: String,
    val categoryColorArgb: Long,
    val note: String,
    val dateIso: String,
    val type: TxType
)

data class CategoryTotal(
    val categoryId: Long,
    val categoryName: String,
    val categoryIcon: String,
    val categoryColorArgb: Long,
    val totalCents: Long
)

data class MonthSummary(
    val yearMonth: String,
    val expenseCents: Long,
    val incomeCents: Long
) {
    val balanceCents: Long get() = incomeCents - expenseCents
}

data class RecurringTransaction(
    val id: Long,
    val amountCents: Long,
    val categoryId: Long,
    val categoryName: String,
    val note: String,
    val dayOfMonth: Int,
    val active: Boolean,
    val type: TxType
)

data class CategoryBudget(
    val categoryId: Long,
    val categoryName: String,
    val amountCents: Long
)

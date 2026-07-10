package com.example.data

import kotlinx.coroutines.flow.Flow

class FinanceRepository(private val financeDao: FinanceDao) {
    val allExpenses: Flow<List<Expense>> = financeDao.getAllExpensesFlow()
    val allSubscriptions: Flow<List<Subscription>> = financeDao.getAllSubscriptionsFlow()
    val budget: Flow<Budget?> = financeDao.getBudgetFlow()

    suspend fun insertExpense(expense: Expense) = financeDao.insertExpense(expense)
    suspend fun deleteExpense(expense: Expense) = financeDao.deleteExpense(expense)
    suspend fun deleteAllExpenses() = financeDao.deleteAllExpenses()

    suspend fun insertSubscription(subscription: Subscription) = financeDao.insertSubscription(subscription)
    suspend fun updateSubscription(subscription: Subscription) = financeDao.updateSubscription(subscription)
    suspend fun deleteSubscription(subscription: Subscription) = financeDao.deleteSubscription(subscription)

    suspend fun getBudgetDirect(): Budget? = financeDao.getBudgetDirect()
    suspend fun insertBudget(budget: Budget) = financeDao.insertBudget(budget)
}

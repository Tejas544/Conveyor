import { KanbanBoard } from '@/components/kanban/KanbanBoard'
import { OrderPlacementForm } from '@/components/OrderPlacementForm'

export function DashboardPage() {
  return (
    <div className="flex h-full flex-col gap-4">
      <div className="flex justify-end">
        <OrderPlacementForm />
      </div>
      <KanbanBoard />
    </div>
  )
}

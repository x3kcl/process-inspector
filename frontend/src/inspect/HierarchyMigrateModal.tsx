// Hierarchy migration plan builder (#497). Stub: the RED commit pins the behaviour first.
interface Props {
  engineId: string
  rootInstanceId: string
  onClose: () => void
}

export function HierarchyMigrateModal({ onClose }: Props) {
  return <div hidden onClick={onClose} />
}

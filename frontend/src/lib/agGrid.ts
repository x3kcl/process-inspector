import { AllCommunityModule, ModuleRegistry, provideGlobalGridOptions } from 'ag-grid-community'

// v36 theme:'legacy' keeps the ag-theme-quartz.css --ag-* overrides (styles.css) valid.
ModuleRegistry.registerModules([AllCommunityModule])
provideGlobalGridOptions({ theme: 'legacy' })

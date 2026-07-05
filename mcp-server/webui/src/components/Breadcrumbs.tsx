import { FileNode } from "../api";
import { ChevronRightIcon, FolderIcon } from "../icons";

interface BreadcrumbsProps {
  path: FileNode[];
  onNavigate: (node: FileNode) => void;
}

export function Breadcrumbs({ path, onNavigate }: BreadcrumbsProps) {
  return (
    <nav className="breadcrumbs" aria-label="Breadcrumb">
      {path.map((node, i) => {
        const isLast = i === path.length - 1;
        return (
          <span key={node.id} className="crumb-group">
            {i > 0 && (
              <ChevronRightIcon size={13} className="crumb-sep" />
            )}
            <button
              className={`crumb ${isLast ? "crumb-current" : ""}`}
              onClick={() => onNavigate(node)}
              disabled={isLast}
            >
              {i === 0 && <FolderIcon size={13} className="crumb-root" />}
              {node.name}
            </button>
          </span>
        );
      })}
    </nav>
  );
}

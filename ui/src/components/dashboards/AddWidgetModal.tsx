import { useState, useMemo } from "react";
import {
    Card,
    CardBody,
    CardTitle,
    Gallery,
    GalleryItem,
    Label,
    Modal,
    ModalBody,
    ModalHeader,
    SearchInput,
    ToggleGroup,
    ToggleGroupItem,
    Toolbar,
    ToolbarContent,
    ToolbarItem,
} from "@patternfly/react-core";
import { getAllWidgets } from "./widget-registry";

interface AddWidgetModalProps {
    isOpen: boolean;
    onSelect: (widgetType: string) => void;
    onCancel: () => void;
}

/**
 * Modal listing all available widget types with search and category filtering.
 */
export function AddWidgetModal({ isOpen, onSelect, onCancel }: AddWidgetModalProps) {
    const [search, setSearch] = useState("");
    const [selectedCategory, setSelectedCategory] = useState("All");

    const allWidgets = getAllWidgets();

    const categories = useMemo(() => {
        const cats = new Set<string>();
        allWidgets.forEach(w => cats.add(w.category));
        return ["All", ...Array.from(cats).sort()];
    }, [allWidgets]);

    const filtered = useMemo(() => {
        const term = search.toLowerCase();
        return allWidgets.filter(w => {
            const matchesCategory = selectedCategory === "All"
                || w.category === selectedCategory;
            const matchesSearch = !term
                || w.name.toLowerCase().includes(term)
                || w.description.toLowerCase().includes(term)
                || w.category.toLowerCase().includes(term);
            return matchesCategory && matchesSearch;
        });
    }, [allWidgets, search, selectedCategory]);

    const handleClose = () => {
        setSearch("");
        setSelectedCategory("All");
        onCancel();
    };

    const handleSelect = (widgetType: string) => {
        setSearch("");
        setSelectedCategory("All");
        onSelect(widgetType);
    };

    return (
        <Modal isOpen={isOpen} onClose={handleClose} variant="large">
            <ModalHeader title="Add Widget" />
            <ModalBody style={{ height: "70vh", overflowY: "auto" }}>
                <Toolbar isSticky>
                    <ToolbarContent>
                        <ToolbarItem>
                            <SearchInput
                                placeholder="Search by name"
                                value={search}
                                onChange={(_e, v) => setSearch(v)}
                                onClear={() => setSearch("")}
                                style={{ minWidth: "240px" }}
                            />
                        </ToolbarItem>
                        <ToolbarItem>
                            <ToggleGroup aria-label="Filter by category">
                                {categories.map(cat => (
                                    <ToggleGroupItem
                                        key={cat}
                                        buttonId={`category-${cat}`}
                                        text={cat}
                                        isSelected={selectedCategory === cat}
                                        onChange={() => setSelectedCategory(cat)}
                                    />
                                ))}
                            </ToggleGroup>
                        </ToolbarItem>
                        <ToolbarItem align={{ default: "alignEnd" }}>
                            <span style={{ color: "var(--pf-t--global--color--200)",
                                           fontSize: "0.875rem" }}>
                                {filtered.length} {filtered.length === 1 ? "widget" : "widgets"}
                            </span>
                        </ToolbarItem>
                    </ToolbarContent>
                </Toolbar>

                <Gallery hasGutter minWidths={{ default: "280px" }}
                         className="widget-gallery"
                         style={{ marginTop: "16px" }}>
                    {filtered.map(widget => (
                        <GalleryItem key={widget.type}>
                            <Card isClickable isCompact isFullHeight
                                  onClick={() => handleSelect(widget.type)}>
                                <CardTitle>{widget.name}</CardTitle>
                                <CardBody>
                                    <Label isCompact color="blue"
                                           style={{ marginBottom: "8px" }}>
                                        {widget.category}
                                    </Label>
                                    <p>{widget.description}</p>
                                </CardBody>
                            </Card>
                        </GalleryItem>
                    ))}
                </Gallery>

                {filtered.length === 0 && (
                    <p style={{ textAlign: "center", padding: "24px",
                               color: "var(--pf-t--global--color--200)" }}>
                        No widgets match your search.
                    </p>
                )}
            </ModalBody>
        </Modal>
    );
}

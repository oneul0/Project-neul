"use client";

import {
  VodLookupSection,
  VodPersonalizationSection,
  VodSelectedVideoSection,
  VodWorkspaceSection,
} from "@/components/vod-highlight-board/sections";
import { useVodHighlightBoard } from "@/components/vod-highlight-board/useVodHighlightBoard";

export default function VodHighlightBoard({
  personalizationEnabled = false,
}: {
  personalizationEnabled?: boolean;
}) {
  const board = useVodHighlightBoard(personalizationEnabled);

  return (
    <div className="space-y-6 overflow-x-hidden">
      <VodLookupSection board={board} />
      <VodSelectedVideoSection board={board} />
      <VodWorkspaceSection board={board} />
      <VodPersonalizationSection
        board={board}
        personalizationEnabled={personalizationEnabled}
      />
    </div>
  );
}

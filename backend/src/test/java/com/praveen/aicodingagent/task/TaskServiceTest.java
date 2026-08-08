package com.praveen.aicodingagent.task;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock
    private TaskRepository taskRepository;

    @InjectMocks
    private TaskService taskService;

    private Task pendingTask(UUID owner) {
        return Task.builder()
                .id(UUID.randomUUID())
                .userId(owner)
                .title("Add pagination")
                .repoUrl("https://github.com/praveen7600/grocery-shop")
                .status(TaskStatus.PENDING)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    @Test
    void allowsPendingToRunning() {
        UUID owner = UUID.randomUUID();
        Task task = pendingTask(owner);
        when(taskRepository.findById(task.getId())).thenReturn(Optional.of(task));

        Task updated = taskService.transitionStatus(task.getId(), owner, TaskStatus.RUNNING);

        assertThat(updated.getStatus()).isEqualTo(TaskStatus.RUNNING);
    }

    @Test
    void rejectsCompletedToRunning() {
        UUID owner = UUID.randomUUID();
        Task task = pendingTask(owner);
        task.setStatus(TaskStatus.COMPLETED);
        when(taskRepository.findById(task.getId())).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> taskService.transitionStatus(task.getId(), owner, TaskStatus.RUNNING))
                .isInstanceOf(InvalidTaskStateTransitionException.class);
    }

    @Test
    void hidesTaskOwnedBySomeoneElseAsNotFound() {
        UUID owner = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        Task task = pendingTask(owner);
        when(taskRepository.findById(task.getId())).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> taskService.getTaskForUser(task.getId(), stranger))
                .isInstanceOf(TaskNotFoundException.class);
    }
}
